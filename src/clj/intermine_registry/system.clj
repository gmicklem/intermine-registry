(ns intermine-registry.system
  "Configuration from the environment, data load, refresh scheduler, Jetty."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [intermine-registry.data :as data]
            [intermine-registry.logos :as logos]
            [intermine-registry.refresh :as refresh]
            [intermine-registry.server :as server]
            [ring.adapter.jetty :refer [run-jetty]]))

(defn- env-int [env k default]
  (let [v (get env k)]
    (if (str/blank? v)
      default
      (try (Integer/parseInt (str/trim v))
           (catch NumberFormatException _
             (throw (ex-info (str k " must be an integer, got " (pr-str v)) {})))))))

(defn- env-non-negative-int [env k default]
  (let [n (env-int env k default)]
    (when (neg? n) (throw (ex-info (str k " must not be negative, got " n) {})))
    n))

(defn config
  "The configuration from environment variables (README, Configuration)."
  ([] (config (System/getenv)))
  ([env]
   (let [env (into {} env)
         blank->nil #(when-not (str/blank? %) %)]
     {:port (env-int env "PORT" 3000)
      :minutes (env-int env "REGISTRY_REFRESH_MINUTES" 360)
      :unreachable-after-hours (env-non-negative-int env "REGISTRY_UNREACHABLE_AFTER_HOURS" 24)
      :cache-dir (or (blank->nil (get env "REGISTRY_CACHE_DIR")) "./cache")
      :admin-token (blank->nil (get env "REGISTRY_ADMIN_TOKEN"))
      :instances-file (blank->nil (get env "REGISTRY_INSTANCES_FILE"))
      :base-url (str/replace (or (blank->nil (get env "REGISTRY_BASE_URL")) "https://registry.intermine.org")
                             #"/+$" "")
      :concurrency 4})))

(defonce ^:private server (atom nil))

(defn stop! []
  (refresh/stop-scheduler!)
  (when-let [s @server] (.stop ^org.eclipse.jetty.server.Server s) (reset! server nil)))

(defn start!
  "Loads the data (throws on an invalid instances file), starts the
  scheduler and Jetty; returns the Jetty server."
  [opts]
  (stop!)
  (data/load! opts)
  (logos/add-missing-tiles! (:cache-dir opts))
  (logos/load-overrides!)
  (refresh/start-scheduler! opts)
  (let [s (run-jetty (server/handler opts) {:port (:port opts) :join? false :send-server-version? false})]
    (reset! server s)
    (log/info "InterMine Registry started on port" (:port opts))
    s))

(defn -main
  "Starts the registry; exits 1 with the problem on stderr when the
  configuration or the instances file is wrong."
  [& _args]
  (let [opts (try (config)
                  (catch clojure.lang.ExceptionInfo e
                    (binding [*out* *err*] (println "Configuration error:" (ex-message e)))
                    (System/exit 2)))]
    (try (start! opts)
         (catch Exception e
           (binding [*out* *err*] (println (or (ex-message e) (str e))))
           (System/exit 1)))))
