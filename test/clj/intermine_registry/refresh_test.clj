(ns intermine-registry.refresh-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [intermine-registry.data :as data]
            [intermine-registry.refresh :as refresh]
            [intermine-registry.test-util :as u])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]))

(use-fixtures :each u/with-bundled-data)

(defn- stub-server
  "An HTTP server on a free port answering path -> [status body] (404
  otherwise). Returns [server base-url]."
  [answers]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [path (.getPath (.getRequestURI ^HttpExchange ex))
                              [status ^String body] (get answers path [404 "Not found"])
                              bytes (.getBytes body "UTF-8")]
                          (.sendResponseHeaders ^HttpExchange ex status (alength bytes))
                          (with-open [os (.getResponseBody ^HttpExchange ex)] (.write os bytes))))))
    (.start server)
    [server (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/testmine")]))

(def previous
  {:id "900" :name "TestMine" :namespace "testmine" :url "http://example.invalid" :isProduction false
   :api_version "34" :intermine_version "5.0.8" :release_version "1.0" :status "Running"
   :colors {:header {:main "#000" :text "#fff"}} :images {:logo "http://example.invalid/logo.png"}})

(def anubis-page "<!doctype html><html lang=en><head><title>Making sure you&#39;re not a bot!</title></head></html>")

(deftest html-answers-are-rejected-and-the-previous-values-kept
  (let [[server url] (stub-server {"/testmine/service/version" [200 anubis-page]
                                   "/testmine/service/version/intermine" [200 "5.1.0"]
                                   "/testmine/service/version/release" [200 (str "<!DOCTYPE html>\n" (apply str (repeat 100 "x")))]
                                   "/testmine/service/branding" [200 anubis-page]})]
    (try
      (let [{:keys [instance errors changes]} (refresh/refresh-instance (assoc previous :url url))]
        (is (= "34" (:api_version instance)) "HTML api_version rejected, previous kept")
        (is (= "1.0" (:release_version instance)) "HTML release rejected, previous kept")
        (is (= "5.1.0" (:intermine_version instance)) "a good value is taken")
        (is (= "Running" (:status instance)))
        (is (= {} (:colors instance)) "unparsable branding: {} as the Node code")
        (is (some #(str/includes? % "/service/version: not a version") errors))
        (is (some #(str/includes? % "/service/version/release") errors))
        (is (= #{"intermine_version" "colors" "images"} (set (map :field changes))))
        (is (data/iso-timestamp? (:last_time_updated instance))))
      (finally (.stop ^HttpServer server 0)))))

(deftest a-failing-mine-is-not-running
  (let [[server url] (stub-server {"/testmine/service/version" [500 "boom"]})]
    (try
      (let [{:keys [instance errors]} (refresh/refresh-instance (assoc previous :url url))]
        (is (= "Not Running" (:status instance)))
        (is (= (select-keys previous [:api_version :intermine_version :release_version :colors :images])
               (select-keys instance [:api_version :intermine_version :release_version :colors :images]))
            "everything else kept")
        (is (= 4 (count errors))))
      (finally (.stop ^HttpServer server 0))))
  (testing "connection refused"
    (let [{:keys [instance errors]} (refresh/refresh-instance (assoc previous :url "http://127.0.0.1:9/nomine"))]
      (is (= "Not Running" (:status instance)))
      (is (seq errors))))
  (testing "a 200 that is not a version means Not Running"
    (let [[server url] (stub-server {"/testmine/service/version/intermine" [200 anubis-page]})]
      (try
        (is (= "Not Running" (:status (:instance (refresh/refresh-instance (assoc previous :url url))))))
        (finally (.stop ^HttpServer server 0))))))

(deftest good-answers-and-the-node-rules
  (let [[server url] (stub-server {"/testmine/service/version" [200 "35\n"]
                                   "/testmine/service/version/intermine" [200 "\"6.0.0\""]
                                   "/testmine/service/version/release" [200 "35"]
                                   "/testmine/service/branding" [200 "{\"properties\":{\"images\":{\"logo\":\"https://x.org/l.png\"},\"colors\":{\"header\":{\"main\":\"#bed73b\",\"text\":\"#000\"}}},\"statusCode\":200}"]})]
    (try
      (let [{:keys [instance]} (refresh/refresh-instance (assoc previous :url url))]
        (is (= "35" (:api_version instance)))
        (is (= "6.0.0" (:intermine_version instance)) "quotes stripped (sanitize)")
        (is (= "" (:release_version instance)) "release equal to the API version is blanked (Node rule)")
        (is (= {:logo "https://x.org/l.png"} (:images instance)))
        (is (= {:header {:main "#bed73b" :text "#000"}} (:colors instance))))
      (finally (.stop ^HttpServer server 0)))))

(deftest refresh-stores-results-and-writes-the-cache
  (let [[server url] (stub-server {"/testmine/service/version" [200 "35"]
                                   "/testmine/service/version/intermine" [200 "5.1.0"]})
        dir (.toFile (java.nio.file.Files/createTempDirectory "registry-cache" (make-array java.nio.file.attribute.FileAttribute 0)))
        inst (assoc previous :url url)]
    (try
      (swap! data/state update :instances conj inst)
      (refresh/refresh! {:cache-dir dir :all? false} [inst])
      (is (= "5.1.0" (:intermine_version (last (data/instances)))))
      (is (= "Running" (get-in @data/state [:refresh :results "testmine" :status])))
      (is (.exists (data/cache-file dir)))
      (is (= "5.1.0" (get-in (data/read-cache dir) [:instances "testmine" :intermine_version])))
      (finally
        (.stop ^HttpServer server 0)
        (doseq [f (reverse (file-seq dir))] (.delete ^java.io.File f))))))

(def demo-mine
  "The local demo mine of the maintenance sprint; the test is skipped when it
  is not running (CI)."
  (or (System/getenv "REGISTRY_TEST_MINE") "http://localhost:18090/humanmine"))

(defn- reachable? [url]
  (let [r (.join (refresh/http-get-async (str url "/service/version")))]
    (= 200 (:status r))))

(deftest demo-mine-refresh
  (if-not (reachable? demo-mine)
    (println "SKIP demo-mine-refresh:" demo-mine "not reachable")
    (let [dev {:id "dev-1" :name "Demo HumanMine" :namespace "demo-humanmine" :url demo-mine
               :isProduction false :api_version "" :intermine_version "" :release_version "" :status "Not Running"}
          {:keys [instance errors changes]} (refresh/refresh-instance dev)]
      (println "demo-mine-refresh" demo-mine "->" (pr-str (select-keys instance [:status :api_version :intermine_version :release_version :images :colors])) "errors:" (pr-str errors))
      (is (= "Running" (:status instance)))
      (is (data/api-version? (:api_version instance)))
      (is (data/version-string? (:intermine_version instance)))
      (is (seq (get-in instance [:images :logo])))
      (is (seq changes)))))
