(ns intermine-registry.refresh-test
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [intermine-registry.data :as data]
            [intermine-registry.refresh :as refresh]
            [intermine-registry.system :as system]
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

(def fetch-failed
  "What `fetch` returns for a mine that answers with a bot-protection page."
  {:intermine {:error "not a version" :reason "bot-protection page"}
   :release {:error "not a version"} :api {:error "not a version"} :branding {:error "HTTP 200"}})

(def fetch-ok
  {:intermine {:value "5.2.0"} :release {:value "1.0"} :api {:value "35"}
   :branding {:colors {} :images {}}})

(defn- step
  "The instance after a check at time t (a pure step: the time is injected)."
  ([inst fetched t] (:instance (refresh/apply-results inst fetched t)))
  ([inst fetched t opts] (:instance (refresh/apply-results inst fetched t opts))))

(deftest failing-longer-than
  (let [f refresh/failing-longer-than?]
    (is (false? (f "2026-11-17T10:00:00.000Z" "2026-11-17T10:00:00.000Z" 24)))
    (is (false? (f "2026-11-17T10:00:00.000Z" "2026-11-18T09:59:59.999Z" 24)))
    (is (false? (f "2026-11-17T10:00:00.000Z" "2026-11-18T10:00:00.000Z" 24)) "exactly 24 h is not more than 24 h")
    (is (true? (f "2026-11-17T10:00:00.000Z" "2026-11-18T10:00:00.001Z" 24)))
    (is (true? (f "2026-11-17T10:00:00.000Z" "2026-11-17T11:00:00.001Z" 1)))
    (is (false? (f nil "2026-11-18T10:00:00.001Z" 24)))))

(deftest unreachable-only-after-24-hours-of-failures
  (let [s0 (assoc previous :last_check {:time t0 :outcome "ok" :reason nil :since nil})
        f1 "2026-11-17T11:00:00.000Z"                       ; the first failure
        s1 (step s0 fetch-failed f1)
        s2 (step s1 fetch-failed "2026-11-17T17:00:00.000Z") ; six hours later
        s3 (step s2 fetch-failed "2026-11-18T11:00:00.000Z") ; exactly 24 h after the first failure
        s4 (step s3 fetch-failed "2026-11-18T17:00:00.000Z") ; 30 h
        s5 (step s4 fetch-ok "2026-11-18T23:00:00.000Z")]
    (testing "first failure: still Running, failure recorded with its time as since"
      (is (= "Running" (:status s1)))
      (is (= {:time f1 :outcome "failed" :reason "bot-protection page" :since f1} (:last_check s1))))
    (testing "six hours of failures: Running, each check recorded, since unchanged"
      (is (= "Running" (:status s2)))
      (is (= {:time "2026-11-17T17:00:00.000Z" :outcome "failed" :reason "bot-protection page" :since f1} (:last_check s2))))
    (testing "exactly 24 h after the first failure: not yet"
      (is (= "Running" (:status s3)))
      (is (= f1 (get-in s3 [:last_check :since]))))
    (testing "more than 24 h: Unreachable, still since the first failure"
      (is (= "Unreachable" (:status s4)))
      (is (= {:time "2026-11-18T17:00:00.000Z" :outcome "failed" :reason "bot-protection page" :since f1} (:last_check s4))))
    (testing "versions and branding kept throughout"
      (doseq [s [s1 s2 s3 s4]] (is (= (select-keys previous kept-keys) (select-keys s kept-keys)))))
    (testing "a success clears it"
      (is (= "Running" (:status s5)))
      (is (= {:time "2026-11-18T23:00:00.000Z" :outcome "ok" :reason nil :since nil} (:last_check s5)))
      (is (= "5.2.0" (:intermine_version s5))))
    (testing "the clock restarts with the next run of failures"
      (let [r (step s5 fetch-failed "2026-11-19T00:00:00.000Z")]
        (is (= "Running" (:status r)))
        (is (= "2026-11-19T00:00:00.000Z" (get-in r [:last_check :since])))))
    (testing "the status is derived from time, not from a count of checks"
      (let [late (step s1 fetch-failed "2026-11-20T11:00:00.000Z")]
        (is (= "Unreachable" (:status late)) "two checks, three days apart (the registry was down)"))
      (let [many (reduce #(step %1 fetch-failed %2) s0
                         (map #(format "2026-11-17T%02d:00:00.000Z" %) (range 11 24)))]
        (is (= "Running" (:status many)) "thirteen failures within 13 hours")))))

(deftest a-mine-listed-unreachable-stays-so-until-a-check-succeeds
  ;; TargetMine, as in instances.edn: Unreachable, failed check "since" a few hours ago
  (let [tm (->> (data/instances) (filter #(= "targetmine" (:namespace %))) first)
        seeded-at (get-in tm [:last_check :since])]
    (is (= "Unreachable" (:status tm)))
    (testing "a failed check shortly after: still Unreachable (not flipped to Running), reason updated"
      (let [s (step tm fetch-failed "2026-11-17T06:00:00.000Z")]
        (is (= "Unreachable" (:status s)))
        (is (= {:time "2026-11-17T06:00:00.000Z" :outcome "failed" :reason "bot-protection page" :since seeded-at}
               (:last_check s)))))
    (testing "a successful check: Running, outcome ok, versions and branding refreshed"
      (let [s (step tm fetch-ok "2026-11-17T06:00:00.000Z")]
        (is (= "Running" (:status s)))
        (is (= {:time "2026-11-17T06:00:00.000Z" :outcome "ok" :reason nil :since nil} (:last_check s)))
        (is (= "2026-11-17T06:00:00.000Z" (:last_time_updated s)))
        (is (= "5.2.0" (:intermine_version s)))))))

(deftest unreachable-hours-are-configurable
  (let [s0 (assoc previous :last_check {:time t0 :outcome "ok" :reason nil :since nil})
        s1 (step s0 fetch-failed t1 {:unreachable-after-hours 1})
        s2 (step s1 fetch-failed "2026-11-17T12:00:00.000Z" {:unreachable-after-hours 1})
        s3 (step s1 fetch-failed "2026-11-17T12:00:00.001Z" {:unreachable-after-hours 1})]
    (is (= "Running" (:status s1)))
    (is (= "Running" (:status s2)) "exactly one hour after the first failure")
    (is (= "Unreachable" (:status s3)))
    (is (= "Running" (:status (step s1 fetch-failed "2026-11-17T12:00:00.001Z" {:unreachable-after-hours 2}))))
    (testing "the default is 24 hours"
      (is (= 24 refresh/default-unreachable-after-hours))
      (is (= "Running" (:status (step s1 fetch-failed "2026-11-18T11:00:00.000Z"))))
      (is (= "Unreachable" (:status (step s1 fetch-failed "2026-11-18T11:00:00.001Z")))))))

(deftest never-checked-mines
  (let [fresh (-> previous (dissoc :last_check :last_time_updated) (assoc :status "Not checked yet"))
        s1 (step fresh fetch-failed t1)
        s2 (step s1 fetch-failed "2026-11-18T11:00:00.000Z")
        s3 (step s2 fetch-failed "2026-11-18T11:00:00.001Z")]
    (testing "a mine that has never succeeded keeps the Not checked yet state while the failures are recent"
      (is (= "Not checked yet" (:status s1)))
      (is (= "failed" (get-in s1 [:last_check :outcome])))
      (is (= "Not checked yet" (:status s2))))
    (testing "and is Unreachable after more than 24 h of failures"
      (is (= "Unreachable" (:status s3))))
    (testing "the first success makes it Running"
      (is (= "Running" (:status (step s1 fetch-ok t2)))))))

(deftest configuration-defaults
  (let [c (system/config {})]
    (is (= 360 (:minutes c)) "every six hours")
    (is (= 24 (:unreachable-after-hours c))))
  (let [c (system/config {"REGISTRY_REFRESH_MINUTES" "60" "REGISTRY_UNREACHABLE_AFTER_HOURS" "48"})]
    (is (= 60 (:minutes c)))
    (is (= 48 (:unreachable-after-hours c))))
  (is (thrown? clojure.lang.ExceptionInfo (system/config {"REGISTRY_UNREACHABLE_AFTER_HOURS" "-1"})))
  (is (thrown? clojure.lang.ExceptionInfo (system/config {"REGISTRY_UNREACHABLE_AFTER_HOURS" "soon"}))))

(deftest a-failing-stub-mine-end-to-end
  (testing "through the real fetch: a failing mine stays Running with the reason"
    (let [bad {"/testmine/service/version/intermine" [200 anubis-page]}
          [server url] (stub-server bad)]
      (try
        (let [{:keys [instance]} (refresh/refresh-instance
                                  (assoc previous :url url :last_check {:time t0 :outcome "ok" :reason nil :since nil}))]
          (is (= "Running" (:status instance)))
          (is (= "bot-protection page" (get-in instance [:last_check :reason])))
          (is (= (:time (:last_check instance)) (:since (:last_check instance)))))
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
