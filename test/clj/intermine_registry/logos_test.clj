(ns intermine-registry.logos-test
  "The logo cache: fetching a mine's logo during the refresh, keeping the
  previous copy when a fetch fails, serving it at /instances/{namespace}/logo,
  images.logo_cached in the API and the pages."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [intermine-registry.data :as data]
            [intermine-registry.logos :as logos]
            [intermine-registry.refresh :as refresh]
            [intermine-registry.test-util :as u])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.file Files]))

(use-fixtures :each u/with-bundled-data)

(def png
  "A 1x1 PNG."
  (byte-array [0x89 0x50 0x4e 0x47 0x0d 0x0a 0x1a 0x0a 0x00 0x00 0x00 0x0d 0x49 0x48 0x44 0x52
               0x00 0x00 0x00 0x01 0x00 0x00 0x00 0x01 0x08 0x06 0x00 0x00 0x00 0x1f 0x15 0xc4
               0x89 0x00 0x00 0x00 0x0d 0x49 0x44 0x41 0x54 0x78 0x9c 0x63 0xf8 0xcf 0xc0 0xf0
               0x1f 0x00 0x05 0x00 0x01 0xff 0x89 0x99 0x3d 0x1d 0x00 0x00 0x00 0x00 0x49 0x45
               0x4e 0x44 0xae 0x42 0x60 0x82]))

(def ico (byte-array (concat [0 0 1 0 1 0 16 16 0 0 1 0 32 0] (repeat 40 0))))

(def html-page (.getBytes "<!DOCTYPE html><html><head><title>Welcome</title></head><body>hi</body></html>" "UTF-8"))

(defn- stub-server
  "An HTTP server on a free port answering path -> [status content-type bytes]
  (or a function returning one, so a test can change what it serves); 404
  otherwise. Returns [server base-url] (the mine is at base-url)."
  [answers]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [path (.getPath (.getRequestURI ^HttpExchange ex))
                              a (get @answers path)
                              [status ct ^bytes body] (if (fn? a) (a) (or a [404 "text/plain" (.getBytes "Not found")]))]
                          (when ct (.add (.getResponseHeaders ^HttpExchange ex) "Content-Type" ct))
                          (.sendResponseHeaders ^HttpExchange ex status (alength body))
                          (with-open [os (.getResponseBody ^HttpExchange ex)] (.write os body))))))
    (.start server)
    [server (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/testmine")]))

(defn- text [s] (.getBytes ^String s "UTF-8"))

(defn- mine-answers
  "The version answers of a working mine, plus extra path -> answer entries."
  [extra]
  (atom (merge {"/testmine/service/version" [200 "text/plain" (text "35")]
                "/testmine/service/version/intermine" [200 "text/plain" (text "5.1.0")]
                "/testmine/service/version/release" [200 "text/plain" (text "1.0")]
                "/testmine/service/branding" [200 "application/json"
                                              (text "{\"properties\":{\"images\":{\"logo\":\"model/images/logo.png\"},\"colors\":{}},\"statusCode\":200}")]}
               extra)))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "registry-logos" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree! [dir] (doseq [f (reverse (file-seq dir))] (.delete ^java.io.File f)))

(def testmine
  {:id "900" :name "TestMine" :namespace "testmine" :isProduction false
   :api_version "34" :intermine_version "5.0.8" :release_version "" :status "Running"
   :colors {} :images {:logo "http://example.invalid/logo.png"}})

(defn- file-bytes [dir name] (vec (Files/readAllBytes (.toPath (io/file dir "logos" name)))))

(defn- with-mine
  "Runs (f dir url answers) against a stub mine and a temporary cache dir,
  with the test mine in state."
  [extra f]
  (let [answers (mine-answers extra)
        [server url] (stub-server answers)
        dir (temp-dir)]
    (try
      (swap! data/state update :instances conj (assoc testmine :url url))
      (f dir url answers)
      (finally (.stop ^HttpServer server 0) (delete-tree! dir)))))

(defn- refresh-testmine! [dir]
  (refresh/refresh! {:cache-dir dir :all? false} [(first (filter #(= "testmine" (:namespace %)) (data/instances)))]))

(deftest check-image-rules
  (is (nil? (logos/check-image "image/png" png)))
  (is (nil? (logos/check-image "image/PNG; charset=binary" png)))
  (is (nil? (logos/check-image "image/x-icon" ico)))
  (is (nil? (logos/check-image "image/vnd.microsoft.icon" ico)))
  (is (nil? (logos/check-image "image/svg+xml" (text "<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\"/>"))))
  (testing "rejected"
    (is (str/includes? (logos/check-image "text/html" html-page) "not an image"))
    (is (str/includes? (logos/check-image "application/octet-stream" png) "not an image"))
    (is (str/includes? (logos/check-image "image/webp" png) "not an image"))
    (is (some? (logos/check-image nil png)))
    (is (= "an HTML page, not an image" (logos/check-image "image/png" html-page)))
    (is (= "not an SVG document" (logos/check-image "image/svg+xml" (text "plain text"))))
    (is (= "empty answer" (logos/check-image "image/png" (byte-array 0))))
    (is (str/includes? (logos/check-image "image/png" (byte-array (inc logos/max-bytes))) "too large"))
    (is (nil? (logos/check-image "image/png" (byte-array logos/max-bytes))) "512 KB exactly is accepted")))

(deftest logo-candidates-order
  (is (= ["https://m.org/mine/model/images/logo.png" "https://m.org/mine/model/images/favicon.ico" "https://m.org/mine/favicon.ico"]
         (refresh/logo-candidates {:url "https://m.org/mine/" :images {:logo "model/images/logo.png"}})))
  (is (= ["https://cdn.org/l.svg" "https://m.org/mine/model/images/favicon.ico" "https://m.org/mine/favicon.ico"]
         (refresh/logo-candidates {:url "https://m.org/mine" :images {:logo "https://cdn.org/l.svg"}})))
  (is (= ["https://m.org/mine/model/images/favicon.ico" "https://m.org/mine/favicon.ico"]
         (refresh/logo-candidates {:url "https://m.org/mine" :images {}}))))

(deftest a-png-is-cached-and-served
  (with-mine {"/testmine/model/images/logo.png" [200 "image/png" png]}
    (fn [dir url _]
      (let [[{:keys [logo errors]}] (refresh-testmine! dir)]
        (is (= "testmine.png" (:file logo)))
        (is (= "image/png" (:content-type logo)))
        (is (= (str url "/model/images/logo.png") (:source logo)) "relative images.logo resolved against the mine")
        (is (= (alength ^bytes png) (:bytes logo)))
        (is (empty? errors))
        (is (= (vec png) (file-bytes dir "testmine.png")))
        (is (= logo (get-in @data/state [:logos "testmine"])) "indexed in the state")
        (is (= logo (get-in (data/read-cache dir) [:logos "testmine"])) "and in the cache EDN")
        (testing "the route"
          (let [resp (u/request :get "/instances/testmine/logo" {:handler-opts {:cache-dir dir}})]
            (is (= 200 (:status resp)))
            (is (= "image/png" (get-in resp [:headers "Content-Type"])))
            (is (= "public, max-age=86400" (get-in resp [:headers "Cache-Control"])))
            (is (= (vec png) (vec (:body resp))))
            (is (= 404 (:status (u/request :get "/instances/flymine/logo" {:handler-opts {:cache-dir dir}}))) "no logo for that mine")
            (is (= 404 (:status (u/request :get "/instances/nosuchmine/logo" {:handler-opts {:cache-dir dir}}))))
            (is (= 404 (:status (u/request :get "/instances/testmine/logo"))) "no cache dir: nothing to serve")))
        (testing "a restart finds it again"
          (data/load! (assoc u/opts :cache-dir dir))
          (is (= logo (get-in @data/state [:logos "testmine"]))))))))

(deftest html-is-rejected-and-the-previous-copy-kept
  (with-mine {"/testmine/model/images/logo.png" [200 "image/png" png]}
    (fn [dir url answers]
      (refresh-testmine! dir)
      (let [before (get-in @data/state [:logos "testmine"])]
        (testing "an HTML page under a text/html content type"
          (swap! answers assoc "/testmine/model/images/logo.png" [200 "text/html; charset=utf-8" html-page]
                 "/testmine/model/images/favicon.ico" [200 "text/html" html-page]
                 "/testmine/favicon.ico" [200 "text/html" html-page])
          (let [[{:keys [logo errors instance]}] (refresh-testmine! dir)]
            (is (nil? logo))
            (is (some #(and (str/starts-with? % "logo: ") (str/includes? % "not an image (text/html)") (str/includes? % "previous copy kept")) errors))
            (is (= "Running" (:status instance)) "the mine itself is fine")
            (is (= before (get-in @data/state [:logos "testmine"])))
            (is (= (vec png) (file-bytes dir "testmine.png")))
            (is (= (vec png) (vec (:body (u/request :get "/instances/testmine/logo" {:handler-opts {:cache-dir dir}})))))))
        (testing "an HTML page under an image content type"
          (swap! answers assoc "/testmine/model/images/logo.png" [200 "image/png" html-page])
          (refresh-testmine! dir)
          (is (= before (get-in @data/state [:logos "testmine"]))))
        (testing "a failing mine: the logo is not even tried, the copy stays"
          (swap! answers assoc "/testmine/service/version/intermine" [503 "text/plain" (text "down")])
          (refresh-testmine! dir)
          (is (= before (get-in @data/state [:logos "testmine"]))))
        (testing "a mine that is down altogether keeps it as well"
          (is (some? (logos/read-logo dir (get-in @data/state [:logos "testmine"])))))))))

(deftest too-large-images-are-rejected
  (with-mine {"/testmine/model/images/logo.png" [200 "image/png" (byte-array (inc logos/max-bytes))]}
    (fn [dir _ _]
      (let [[{:keys [logo errors]}] (refresh-testmine! dir)]
        (is (nil? logo))
        (is (some #(str/includes? % "too large") errors))
        (is (not (.exists (io/file dir "logos" "testmine.png"))))
        (is (nil? (get-in @data/state [:logos "testmine"])))))))

(deftest the-favicon-is-the-second-choice-and-replaces-the-old-file
  (with-mine {"/testmine/model/images/logo.png" [200 "image/png" png]}
    (fn [dir _ answers]
      (refresh-testmine! dir)
      (is (.exists (io/file dir "logos" "testmine.png")))
      (swap! answers assoc "/testmine/model/images/logo.png" [404 "text/plain" (text "gone")]
             "/testmine/model/images/favicon.ico" [200 "image/x-icon" ico])
      (let [[{:keys [logo errors]}] (refresh-testmine! dir)]
        (is (= "testmine.ico" (:file logo)))
        (is (= "image/x-icon" (:content-type logo)))
        (is (str/ends-with? (:source logo) "/testmine/model/images/favicon.ico"))
        (is (empty? errors))
        (is (not (.exists (io/file dir "logos" "testmine.png"))) "the old file is gone")
        (is (= (vec ico) (file-bytes dir "testmine.ico"))))
      (testing "then /favicon.ico"
        (swap! answers assoc "/testmine/model/images/favicon.ico" [404 "text/plain" (text "no")]
               "/testmine/favicon.ico" [200 "image/vnd.microsoft.icon" ico])
        (let [[{:keys [logo]}] (refresh-testmine! dir)]
          (is (= "image/vnd.microsoft.icon" (:content-type logo)))
          (is (str/ends-with? (:source logo) "/testmine/favicon.ico")))))))

(deftest invalid-index-entries-are-ignored-on-load
  (let [dir (temp-dir)]
    (try
      (spit (data/cache-file dir)
            (pr-str {:logos {"flymine" {:file "flymine.png" :content-type "image/png"}
                             "mousemine" {:file "../../etc/passwd.png" :content-type "image/png"}
                             "humanmine" {:file "humanmine.html" :content-type "text/html"}}}))
      (data/load! (assoc u/opts :cache-dir dir))
      (is (= #{"flymine"} (set (keys (:logos @data/state)))))
      (finally (delete-tree! dir)))))

(defn- cached-state! [dir ns]
  (let [entry (logos/store! dir ns {:content-type "image/png" :body png :source "x"} nil "2026-11-17T10:00:00.000Z")]
    (swap! data/state assoc-in [:logos ns] entry)
    entry))

(deftest api-exposes-logo-cached
  (let [flymine-own (get-in (first (filter #(= "flymine" (:namespace %)) (data/instances))) [:images :logo])
        dir (temp-dir)
        fly (fn [uri] (let [b (u/body-json (u/request :get uri {:handler-opts {:base-url "https://reg.example.org"}}))]
                        (or (get b "instance")
                            (first (filter #(= "flymine" (get % "namespace")) (get b "instances"))))))]
    (try
      (testing "nothing cached: no logo_cached, images.logo as before"
        (is (not (contains? (get (fly "/service/instances/flymine") "images") "logo_cached")))
        (is (= flymine-own (get-in (fly "/service/instances/flymine") ["images" "logo"]))))
      (cached-state! dir "flymine")
      (testing "cached: absolute URL from REGISTRY_BASE_URL, images.logo untouched"
        (doseq [uri ["/service/instances/flymine" "/service/instances"]]
          (is (= "https://reg.example.org/instances/flymine/logo" (get-in (fly uri) ["images" "logo_cached"])) uri)
          (is (= flymine-own (get-in (fly uri) ["images" "logo"])) uri))
        (is (not (contains? (get (u/body-json (u/request :get "/service/instances/mousemine")) "instance") "logo_cached"))))
      (finally (delete-tree! dir)))))

(deftest pages-use-the-registry-copy
  (let [dir (temp-dir)
        flymine-own (get-in (first (filter #(= "flymine" (:namespace %)) (data/instances))) [:images :logo])
        page (fn [uri] (:body (u/request :get uri)))]
    (try
      (testing "before the first refresh: only the initial marks, never the mine's own host"
        (let [home (page "/")]
          (is (not (str/includes? home "<img")))
          (is (not (str/includes? home flymine-own)))
          (is (str/includes? home "class=\"placeholder\""))))
      (cached-state! dir "flymine")
      (testing "cached: the registry path, the initial mark kept as the fallback"
        (let [home (page "/")
              inst (page "/instances/flymine")]
          (is (str/includes? home "src=\"/instances/flymine/logo\""))
          (is (not (str/includes? home "<img src=\"https://")))
          (is (str/includes? home "class=\"placeholder\""))
          (is (str/includes? inst "src=\"/instances/flymine/logo\""))
          (is (str/includes? inst "logo_cached"))
          (is (not (str/includes? (page "/instances/mousemine") "src=\"/instances/mousemine/logo\"")))))
      (finally (delete-tree! dir)))))
