(ns intermine-registry.test-util
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [intermine-registry.data :as data]
            [intermine-registry.server :as server]))

(def opts {:port 0 :minutes 0 :cache-dir nil :admin-token nil :instances-file nil
           :base-url "https://registry.intermine.org" :concurrency 4})

(defn load-bundled! [] (data/load! opts))

(defn with-bundled-data [f] (load-bundled!) (f))

(defn request
  ([method uri] (request method uri {}))
  ([method uri {:keys [query headers handler-opts]}]
   ((server/handler (merge opts handler-opts))
    {:request-method method :uri uri :query-string query :headers (or headers {})
     :scheme :http :server-name "localhost" :server-port 80 :remote-addr "127.0.0.1"})))

(defn body-json [resp] (json/parse-string (:body resp)))

(defn capture [file] (json/parse-string (slurp (io/resource (str "capture-2026-11-17/" file)))))
