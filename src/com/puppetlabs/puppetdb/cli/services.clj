;; ## Main entrypoint
;;
;; PuppetDB consists of several, cooperating components:
;;
;; * Command processing
;;
;;   PuppetDB uses a CQRS pattern for making changes to its domain
;;   objects (facts, catalogs, etc). Instead of simply submitting data
;;   to PuppetDB and having it figure out the intent, the intent
;;   needs to explicitly be codified as part of the operation. This is
;;   known as a "command" (e.g. "replace the current facts for node
;;   X").
;;
;;   Commands are processed asynchronously, however we try to do our
;;   best to ensure that once a command has been accepted, it will
;;   eventually be executed. Ordering is also preserved. To do this,
;;   all incoming commands are placed in a message queue which the
;;   command processing subsystem reads from in FIFO order.
;;
;;   Refer to `com.puppetlabs.puppetdb.command` for more details.
;;
;; * Message queue
;;
;;   We use an embedded instance of AciveMQ to handle queueing duties
;;   for the command processing subsystem. The message queue is
;;   persistent, and it only allows connections from within the same
;;   VM.
;;
;;   Refer to `com.puppetlabs.mq` for more details.
;;
;; * REST interface
;;
;;   All interaction with PuppetDB is conducted via its REST API. We
;;   embed an instance of Jetty to handle web server duties. Commands
;;   that come in via REST are relayed to the message queue. Read-only
;;   requests are serviced synchronously.
;;
;; * Database sweeper
;;
;;   As catalogs are modified, unused records may accumulate and stale
;;   data may linger in the database. We periodically sweep the
;;   database, compacting it and performing regular cleanup so we can
;;   maintain acceptable performance.
;;
(ns com.puppetlabs.puppetdb.cli.services
  (:require [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.puppetdb.scf.migrate :as migrations]
            [com.puppetlabs.puppetdb.command :as command]
            [com.puppetlabs.puppetdb.query.population :as pop]
            [com.puppetlabs.jdbc :as pl-jdbc]
            [com.puppetlabs.jetty :as jetty]
            [com.puppetlabs.mq :as mq]
            [com.puppetlabs.utils :as pl-utils]
            [clojure.java.jdbc :as sql]
            [clojure.tools.logging :as log]
            [cheshire.core :as json]
            [com.puppetlabs.puppetdb.http.server :as server])
  (:use [clojure.java.io :only [file]]
        [clj-time.core :only [ago secs days]]
        [clojure.core.incubator :only (-?>)]
        [com.puppetlabs.time :only [to-secs parse-period format-period]]
        [com.puppetlabs.jdbc :only (with-transacted-connection)]
        [com.puppetlabs.utils :only (cli! configure-logging! inis-to-map with-error-delivery)]
        [com.puppetlabs.repl :only (start-repl)]
        [com.puppetlabs.puppetdb.scf.migrate :only [migrate!]]
        [com.puppetlabs.puppetdb.version :only [version update-info]]))

(def cli-description "Main PuppetDB daemon")

;; ## Wiring
;;
;; The following functions setup interaction between the main
;; PuppetDB components.

(def configuration nil)
(def mq-addr "vm://localhost?jms.prefetchPolicy.all=1&create=false")
(def mq-endpoint "com.puppetlabs.puppetdb.commands")
(def send-command! (partial command/enqueue-command! mq-addr mq-endpoint))

(defn load-from-mq
  "Process commands from the indicated endpoint on the supplied message queue.

  This function doesn't terminate. If we encounter an exception when
  processing commands from the message queue, we retry the operation
  after reopening a fresh connection with the MQ."
  [mq mq-endpoint discard-dir db]
  (pl-utils/keep-going
   (fn [exception]
     (log/error exception "Error during command processing; reestablishing connection after 10s")
     (Thread/sleep 10000))

   (with-open [conn (mq/connect! mq)]
     (command/process-commands! conn mq-endpoint discard-dir {:db db}))))

(defn sweep-database!
  "Sweep the indicated database every `interval` minutes.

  This function doesn't terminate. If we encounter an exception during
  compaction, the operation will be retried after `interval` millis.

  This function will perform database garbage collection.  This entails
  cleaning out catalogs and facts that are no longer in use, deactivating nodes
  that haven't been active in the last `node-ttl-seconds`, and clearing out
  reports that are older than `report-ttl-seconds`"
  [db interval node-ttl-seconds report-ttl-seconds]
  {:pre [(pos? interval)
         (>= node-ttl-seconds 0)
         (>= report-ttl-seconds 0)]}
  (let [sleep #(Thread/sleep (* 60 1000 interval))]
    (pl-utils/keep-going
     (fn [exception]
       (log/error exception "Error during database sweep")
       (sleep))

     (pl-utils/demarcate
      "database garbage collection"
      (with-transacted-connection db
        (scf-store/garbage-collect!)))

     (when (pos? node-ttl-seconds)
       (pl-utils/demarcate
        (format "sweep of stale nodes (threshold: %s)"
          (format-period (secs node-ttl-seconds)))
        (with-transacted-connection db
          (doseq [node (scf-store/stale-nodes (ago (secs node-ttl-seconds)))]
            (send-command! "deactivate node" 1 (json/generate-string node))))))

     (when (pos? report-ttl-seconds)
       (pl-utils/demarcate
        (format "sweep of stale reports (threshold: %s)"
          (format-period (secs report-ttl-seconds)))
        (with-transacted-connection db
          (scf-store/delete-reports-older-than! (ago (secs report-ttl-seconds))))))

     (sleep))))

(defn check-for-updates
  "This will fetch the latest version number of PuppetDB and log if the system
  is out of date."
  [update-server db]
  (let [{:keys [version newer link]} (try
                                       (update-info update-server db)
                                       (catch Throwable e
                                         (log/debug e (format "Could not retrieve update information (%s)" update-server))))
        link-str                     (if link
                                       (format "Visit %s for details." link)
                                       "")
        update-msg                   (format "Newer version %s is available! %s" version link-str)]
    (when newer
      (log/info update-msg))))

(defn configure-commandproc-threads
  "Update the supplied config map with the number of
  command-processing threads to use. If no value exists in the config
  map, default to half the number of CPUs. If only one CPU exists, we
  will use one command-processing thread."
  [config]
  {:pre  [(map? config)]
   :post [(map? %)
          (pos? (get-in % [:command-processing :threads]))]}
  (let [default-nthreads (-> (pl-utils/num-cpus)
                             (/ 2)
                             (int)
                             (max 1))]
    (update-in config [:command-processing :threads] #(or % default-nthreads))))

(defn configure-web-server
  "Update the supplied config map with information about the HTTP webserver to
  start. This will specify client auth, and add a default host/port
  http://puppetdb:8080 if none are supplied (and SSL is not specified)."
  [config]
  {:pre  [(map? config)]
   :post [(map? config)]}
  (assoc-in config [:jetty :client-auth] :need))

(defn configure-node-ttl
  "Helper function that parses the `node-ttl` setting from the config file
  (if present) into our internal representation (in seconds).  Also supports
  `node-ttl-days` for backward compatibility with previous versions."
  [database]
  {:pre  [(map? database)]
   :post [(map? %)
          (not (contains? % :node-ttl))
          (not (contains? % :node-ttl-days))]}
  (cond
    (:node-ttl database)      (-> database
                                  (assoc :node-ttl-seconds
                                    (to-secs (parse-period (:node-ttl database))))
                                  (dissoc :node-ttl)
                                  (dissoc :node-ttl-days))
    (:node-ttl-days database) (-> database
                                  (assoc :node-ttl-seconds
                                    (to-secs (days (:node-ttl-days database))))
                                  (dissoc :node-ttl-days))
    :else                     database))

(defn configure-report-ttl
  "Helper function that parses the `report-ttl` setting from the config file
  (if present) into our internal representation (in seconds)"
  [database]
  {:pre  [(map? database)]
   :post [(map? %)
          (not (contains? % :report-ttl))]}
  (if-let [report-ttl (:report-ttl database)]
    (-> database
      (assoc :report-ttl-seconds (to-secs (parse-period report-ttl)))
      (dissoc :report-ttl))
    database))

(defn configure-database-ttls
  "Helper function that munges the supported permutations of our `ttl` settings
  (if present) from their config file representation to our internal
  representation (in seconds)"
  [database]
  {:pre  [(map? database)]
   :post [(map? database)
          (not (contains? % :report-ttl))
          (not (contains? % :node-ttl))
          (not (contains? % :node-ttl-days))]}
  (-> database
      configure-report-ttl
      configure-node-ttl))

(defn configure-database
  "Update the supplied config map with information about the database. Adds a
  default hsqldb and a gc-interval of 60 minutes. If a single part of the
  database information is specified (such as classname but not subprotocol), no
  defaults will be filled in."
  [{:keys [database global] :as config}]
  {:pre  [(map? config)]
   :post [(map? config)]}
  (let [vardir       (:vardir global)
        default-db   {:classname   "org.hsqldb.jdbcDriver"
                      :subprotocol "hsqldb"
                      :subname     (format "file:%s;hsqldb.tx=mvcc;sql.syntax_pgs=true" (file vardir "db"))}
        default-opts {:gc-interval        60
                      :node-ttl-seconds   0
                      :report-ttl-seconds (to-secs (days 7))}
        db           (configure-database-ttls (or database default-db))]
    (assoc config :database (merge default-opts db))))

(defn validate-vardir
  "Checks that `vardir` is specified, exists, and is writeable, throwing
  appropriate exceptions if any condition is unmet."
  [vardir]
  (if-let [vardir (file vardir)]
    (cond
     (not (.isAbsolute vardir))
     (throw (IllegalArgumentException.
             (format "Vardir %s must be an absolute path." vardir)))

     (not (.exists vardir))
     (throw (java.io.FileNotFoundException.
             (format "Vardir %s does not exist. Please create it and ensure it is writable." vardir)))

     (not (.isDirectory vardir))
     (throw (java.io.FileNotFoundException.
             (format "Vardir %s is not a directory." vardir)))

     (not (.canWrite vardir))
     (throw (java.io.FileNotFoundException.
             (format "Vardir %s is not writable." vardir))))

    (throw (IllegalArgumentException.
            "Required setting 'vardir' is not specified. Please set it to a writable directory.")))
  vardir)

(defn set-global-configuration!
  "Store away global configuration"
  [config]
  {:pre  [(map? config)]
   :post [(map? %)]}
  (def configuration config)
  config)

(defn parse-config!
  "Parses the given config file/directory and configures its various
  subcomponents.

  Also accepts an optional map argument 'initial-config'; if
  provided, any initial values in this map will be included
  in the resulting config map."
  ([path]
    (parse-config! path {}))
  ([path initial-config]
    {:pre [(string? path)
           (map? initial-config)]}
    (let [file (file path)]
      (when-not (.canRead file)
        (throw (IllegalArgumentException.
                (format "Configuration path '%s' must exist and must be readable." path)))))

    (-> (merge initial-config (inis-to-map path))
        (configure-logging!)
        (configure-commandproc-threads)
        (configure-web-server)
        (configure-database)
        (set-global-configuration!))))

(defn on-shutdown
  "General cleanup when a shutdown request is received."
  []
  ;; nothing much to do here for now, but let's at least log that we're shutting down.
  (log/info "Shutdown request received; puppetdb exiting."))

(defn -main
  [& args]
  (let [[options _]                                (cli! args)
        initial-config                             {:debug (:debug options)}
        {:keys [jetty database global command-processing]
            :as config}                            (parse-config! (:config options) initial-config)
        vardir                                     (validate-vardir (:vardir global))
        update-server                              (:update-server global "http://updates.puppetlabs.com/check-for-updates")
        resource-query-limit                       (get global :resource-query-limit 20000)
        db                                         (pl-jdbc/pooled-datasource database)
        db-gc-minutes                              (get database :gc-interval)
        node-ttl-seconds                           (get database :node-ttl-seconds)
        report-ttl-seconds                         (get database :report-ttl-seconds)
        mq-dir                                     (str (file vardir "mq"))
        discard-dir                                (file mq-dir "discarded")
        globals                                    {:scf-db               db
                                                    :command-mq           {:connection-string mq-addr
                                                                           :endpoint          mq-endpoint}
                                                    :resource-query-limit resource-query-limit
                                                    :update-server        update-server}]



    (when (version)
      (log/info (format "PuppetDB version %s" (version))))

    ;; Add a shutdown hook where we can handle any required cleanup
    (pl-utils/add-shutdown-hook! on-shutdown)

    ;; Ensure the database is migrated to the latest version, and warn if it's
    ;; deprecated. We do this in a single connection because HSQLDB seems to
    ;; get confused if the database doesn't exist but we open and close a
    ;; connection without creating anything.
    (sql/with-connection db
      (scf-store/warn-on-db-deprecation!)
      (migrate!))

    ;; Initialize database-dependent metrics
    (pop/initialize-metrics db)

    (let [error         (promise)
          broker        (try
                          (log/info "Starting broker")
                          (mq/build-and-start-broker! "localhost" mq-dir command-processing)
                          (catch java.io.EOFException e
                            (log/error
                              "EOF Exception caught during broker start, this "
                              "might be due to KahaDB corruption. Consult the "
                              "PuppetDB troubleshooting guide.")
                            (throw e)))
          command-procs (let [nthreads (command-processing :threads)]
                          (log/info (format "Starting %d command processor threads" nthreads))
                          (vec (for [n (range nthreads)]
                                 (future (with-error-delivery error
                                           (load-from-mq mq-addr mq-endpoint discard-dir db))))))
          updater       (future (check-for-updates update-server db))
          web-app       (let [authorized? (if-let [wl (jetty :certificate-whitelist)]
                                            (pl-utils/cn-whitelist->authorizer wl)
                                            (constantly true))
                              app         (server/build-app :globals globals :authorized? authorized?)]
                          (log/info "Starting query server")
                          (future (with-error-delivery error
                                    (jetty/run-jetty app jetty))))
          db-gc         (do
                          (log/info (format "Starting database sweeper (%d minute interval)" db-gc-minutes))
                          (future (with-error-delivery error
                                    (sweep-database! db db-gc-minutes node-ttl-seconds report-ttl-seconds))))]

      ;; Start debug REPL if necessary
      (let [{:keys [enabled type host port] :or {type "nrepl" host "localhost"}} (:repl config)]
        (when (= "true" enabled)
          (log/warn (format "Starting %s server on port %d" type port))
          (start-repl type host port)))

      (let [exception (deref error)]
        (doseq [cp command-procs]
          (future-cancel cp))
        (future-cancel updater)
        (future-cancel web-app)
        (future-cancel db-gc)
        ;; Stop the mq the old-fashioned way
        (mq/stop-broker! broker)

        ;; Now throw the exception so the top-level handler will see it
        (throw exception)))))
