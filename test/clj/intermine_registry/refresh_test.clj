(ns intermine-registry.refresh-test
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
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

(def t0 "2026-11-17T10:00:00.000Z")
(def t1 "2026-11-17T11:00:00.000Z")
(def t2 "2026-11-17T12:00:00.000Z")

(def kept-keys [:api_version :intermine_version :release_version :colors :images])

(defn- check-once
  "refresh-instance of inst against a stub answering answers; the instance."
  [inst answers]
  (let [[server url] (stub-server answers)]
    (try (:instance (refresh/refresh-instance (assoc inst :url url)))
         (finally (.stop ^HttpServer server 0)))))

(deftest a-failing-mine-keeps-its-values-and-records-the-reason
  (let [inst (check-once previous {"/testmine/service/version" [500 "boom"]
                                   "/testmine/service/version/intermine" [503 "down"]})]
    (is (= "failed" (get-in inst [:last_check :outcome])))
    (is (= "http 503" (get-in inst [:last_check :reason])))
    (is (data/iso-timestamp? (get-in inst [:last_check :time])))
    (is (= (select-keys previous kept-keys) (select-keys inst kept-keys)) "versions and branding kept")
    (is (s/valid? ::data/last_check (:last_check inst)) "valid for the cache")
    (is (every? #(data/valid-refreshed-value? % (get inst %)) data/refreshed-keys))))

(deftest failure-reasons
  (let [reason (fn [answers] (get-in (check-once previous answers) [:last_check :reason]))
        i "/testmine/service/version/intermine"]
    (testing "bot protection pages, whatever the status"
      (is (= "bot-protection page" (reason {i [200 anubis-page]})) "title: not a bot")
      (is (= "bot-protection page" (reason {i [200 "<html><body>Protected by Anubis from TecharoHQ</body></html>"]})))
      (is (= "bot-protection page" (reason {i [200 "<html><head><meta name=\"generator\" content=\"Anubis\"></head></html>"]})))
      (is (= "bot-protection page" (reason {i [200 "<html><head><meta name=\"generator\" content=\"x\"></head></html>"]})))
      (is (= "bot-protection page" (reason {i [200 "<!doctype html><title>Security Challenge</title>"]})))
      (is (= "bot-protection page" (reason {i [403 "<!doctype html><title>Human verification</title>"]}))))
    (testing "other HTML is html instead of json"
      (is (= "html instead of json" (reason {i [200 "<!DOCTYPE html><html><head><title>Welcome</title></head></html>"]}))))
    (testing "status codes"
      (is (= "http 503" (reason {i [503 "unavailable"]})))
      (is (= "http 404" (reason {})) "no answer registered: the stub answers 404")
      (is (= "http 403" (reason {i [403 "Forbidden"]}))))
    (testing "a 200 that is not a version"
      (is (= "invalid version string" (reason {i [200 "something else entirely, not a version at all, far too long to be one, really"]})))
      (is (= "invalid version string" (reason {i [200 ""]}))))
    (testing "exceptions"
      (is (= "connection refused" (get-in (:instance (refresh/refresh-instance (assoc previous :url "http://127.0.0.1:9/nomine")))
                                          [:last_check :reason]))))
    (testing "timeout"
      (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
        (.createContext server "/" (reify HttpHandler (handle [_ ex] (Thread/sleep 1500) (.close ^HttpExchange ex))))
        (.start server)
        (try
          (binding [refresh/*timeout-ms* 300]
            (is (= "timeout" (get-in (:instance (refresh/refresh-instance
                                                 (assoc previous :url (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/m"))))
                                     [:last_check :reason]))))
          (finally (.stop ^HttpServer server 0)))))))

(deftest classify-failure-unit
  (is (= "timeout" (refresh/classify-failure {:error "HttpConnectTimeoutException: x" :error-class "HttpConnectTimeoutException"})))
  (is (= "timeout" (refresh/classify-failure {:error-class "ConnectException" :error-message "Connection timed out"})))
  (is (= "unknown host" (refresh/classify-failure {:error-class "UnknownHostException"})))
  (is (= "TLS error" (refresh/classify-failure {:error-class "SSLHandshakeException"})))
  (is (= "bad URL" (refresh/classify-failure {:error-class "BadUrl"})))
  (is (= "answer too large" (refresh/classify-failure {:status 200 :body (apply str (repeat 70000 "1"))}))))

(deftest two-strikes-rule
  (let [bad {"/testmine/service/version/intermine" [200 anubis-page]}
        good {"/testmine/service/version" [200 "35"] "/testmine/service/version/intermine" [200 "5.2.0"]}
        step (fn [inst answers t]
               (let [[server url] (stub-server answers)]
                 (try (:instance (refresh/apply-results inst (refresh/fetch url) t))
                      (finally (.stop ^HttpServer server 0)))))
        s0 (assoc previous :last_check {:time t0 :outcome "ok" :reason nil :since nil})
        s1 (step s0 bad t1)
        s2 (step s1 bad t2)
        s3 (step s2 good "2026-11-17T13:00:00.000Z")]
    (testing "first failure: still Running, failure recorded"
      (is (= "Running" (:status s1)))
      (is (= {:time t1 :outcome "failed" :reason "bot-protection page" :since t1} (:last_check s1))))
    (testing "second consecutive failure: Unreachable, since the first failure"
      (is (= "Unreachable" (:status s2)))
      (is (= {:time t2 :outcome "failed" :reason "bot-protection page" :since t1} (:last_check s2))))
    (testing "versions and branding kept throughout"
      (is (= (select-keys previous kept-keys) (select-keys s2 kept-keys))))
    (testing "a success clears it"
      (is (= "Running" (:status s3)))
      (is (= {:time "2026-11-17T13:00:00.000Z" :outcome "ok" :reason nil :since nil} (:last_check s3)))
      (is (= "5.2.0" (:intermine_version s3))))
    (testing "a failure between two successes never makes it Unreachable"
      (is (= "Running" (:status (step (step s3 bad t1) good t2)))))
    (testing "never checked and failing: Unreachable at once (nothing to keep)"
      (let [fresh (-> previous (dissoc :last_check :last_time_updated) (assoc :status "Not checked yet"))
            r (step fresh bad t1)]
        (is (= "Unreachable" (:status r)))
        (is (= "failed" (get-in r [:last_check :outcome])))))))

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
      (is (= "ok" (get-in (last (data/instances)) [:last_check :outcome])))
      (is (= "ok" (get-in (data/read-cache dir) [:instances "testmine" :last_check :outcome])) "last_check is cached")
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
               :isProduction false :api_version "" :intermine_version "" :release_version "" :status "Not checked yet"}
          {:keys [instance errors changes]} (refresh/refresh-instance dev)]
      (println "demo-mine-refresh" demo-mine "->" (pr-str (select-keys instance [:status :api_version :intermine_version :release_version :images :colors])) "errors:" (pr-str errors))
      (is (= "Running" (:status instance)))
      (is (data/api-version? (:api_version instance)))
      (is (data/version-string? (:intermine_version instance)))
      (is (seq (get-in instance [:images :logo])))
      (is (seq changes)))))
