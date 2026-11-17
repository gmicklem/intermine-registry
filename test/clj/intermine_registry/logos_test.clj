(ns intermine-registry.logos-test
  "The logo cache: fetching a mine's logo during the refresh, keeping the
  previous copy when a fetch fails, serving it at /instances/{namespace}/logo,
  images.logo_cached in the API and the pages; the tile shade of each logo
  (images.logo_tile); the logo overrides bundled with the registry
  (:logo-override, resources/public/logos/)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [intermine-registry.data :as data]
            [intermine-registry.logos :as logos]
            [intermine-registry.refresh :as refresh]
            [intermine-registry.test-util :as u])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.awt.image BufferedImage]
           [java.io ByteArrayOutputStream]
           [java.net InetSocketAddress]
           [java.nio.file Files]
           [javax.imageio ImageIO]))

(use-fixtures :each u/with-bundled-data)

(def png
  "A 1x1 PNG."
  (byte-array [0x89 0x50 0x4e 0x47 0x0d 0x0a 0x1a 0x0a 0x00 0x00 0x00 0x0d 0x49 0x48 0x44 0x52
               0x00 0x00 0x00 0x01 0x00 0x00 0x00 0x01 0x08 0x06 0x00 0x00 0x00 0x1f 0x15 0xc4
               0x89 0x00 0x00 0x00 0x0d 0x49 0x44 0x41 0x54 0x78 0x9c 0x63 0xf8 0xcf 0xc0 0xf0
               0x1f 0x00 0x05 0x00 0x01 0xff 0x89 0x99 0x3d 0x1d 0x00 0x00 0x00 0x00 0x49 0x45
               0x4e 0x44 0xae 0x42 0x60 0x82]))

(defn- image
  "A w x h ARGB image, each pixel (f x y) -> 0xAARRGGBB."
  ^BufferedImage [w h f]
  (let [img (BufferedImage. w h BufferedImage/TYPE_INT_ARGB)]
    (doseq [x (range w) y (range h)] (.setRGB img (int x) (int y) (unchecked-int (f x y))))
    img))

(defn- encode
  "img as bytes in the format (\"png\", \"gif\", \"jpg\"; jpg needs an RGB image)."
  ^bytes [^BufferedImage img fmt]
  (let [out (ByteArrayOutputStream.)]
    (ImageIO/write img ^String fmt out)
    (.toByteArray out)))

(def white 0xffffffff)
(def black 0xff000000)
(def clear 0x00000000)

(def white-png (encode (image 8 8 (constantly white)) "png"))

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

(deftest tile-shade-from-luminance
  (testing "luminance of synthetic images"
    (is (= 255.0 (Math/rint (logos/image-luminance (image 4 4 (constantly white))))))
    (is (= 0.0 (logos/image-luminance (image 4 4 (constantly black)))))
    (is (nil? (logos/image-luminance (image 4 4 (constantly clear)))) "nothing visible")
    (is (= 0.0 (logos/image-luminance (image 4 4 (fn [x y] (if (= [x y] [1 1]) black clear)))))
        "transparent pixels are ignored")
    (is (= 0.0 (logos/image-luminance (image 4 4 (fn [x _] (if (zero? x) black 0x20ffffff)))))
        "faint pixels (alpha 32 or less) are ignored")
    (is (< 169 (logos/image-luminance (image 2 1 (fn [x _] (if (zero? x) 0xffffffff 0x80000000)))) 170)
        "weighted by alpha: (255 * 255 + 0 * 128) / (255 + 128) = 169.8")
    (is (< 182 (logos/image-luminance (image 1 1 (constantly 0xff00ff00))) 183) "green counts 0.7152"))
  (testing "the shade: a light logo gets the dark tile"
    (is (= "dark" (logos/image-tile (image 4 4 (constantly white)))))
    (is (= "light" (logos/image-tile (image 4 4 (constantly black)))))
    (is (= "light" (logos/image-tile (image 4 4 (fn [x y] (if (= [x y] [2 3]) black clear)))))
        "transparent except a dark pixel")
    (is (= "dark" (logos/image-tile (image 4 4 (fn [x y] (if (= [x y] [2 3]) white clear)))))
        "transparent except a white pixel (a white logo on a transparent background)")
    (is (= "light" (logos/image-tile (image 4 4 (constantly clear)))) "nothing visible: light")
    (is (= "dark" (logos/shade-for-luminance logos/light-logo-luminance)))
    (is (= "light" (logos/shade-for-luminance (dec logos/light-logo-luminance))))
    (is (= "light" (logos/shade-for-luminance nil))))
  (testing "the threshold sorts the mines' logos of Nov 2026 (measured luminance)"
    (is (= {"aquaMine" "dark" "thalemine" "dark" "flymine" "light" "mousemine" "light" "maizemine" "light"
            "planmine" "light" "humanmine" "light" "hymenopteramine" "light" "faangmine" "light"}
           (update-vals {"aquaMine" 240 "thalemine" 208 "flymine" 147 "mousemine" 147 "maizemine" 139
                         "planmine" 127 "humanmine" 74 "hymenopteramine" 19 "faangmine" 13}
                        logos/shade-for-luminance))))
  (testing "from the stored bytes"
    (is (= "dark" (logos/logo-tile white-png)))
    (is (= "light" (logos/logo-tile (encode (image 8 8 (constantly black)) "png"))))
    (is (= "dark" (logos/logo-tile (encode (image 8 8 (fn [x _] (if (< x 4) white clear))) "gif"))) "GIF")
    (let [rgb (BufferedImage. 8 8 BufferedImage/TYPE_INT_RGB)]
      (doseq [x (range 8) y (range 8)] (.setRGB rgb (int x) (int y) (unchecked-int white)))
      (is (= "dark" (logos/logo-tile (encode rgb "jpg"))) "JPEG"))
    (is (= "dark" (logos/logo-tile (encode (image 2000 1500 (constantly white)) "png"))) "a large image is subsampled")
    (testing "what javax.imageio cannot decode: light"
      (is (= "light" (logos/logo-tile ico)) "ICO")
      (is (= "light" (logos/logo-tile (text "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect fill=\"#fff\"/></svg>"))) "SVG")
      (is (= "light" (logos/logo-tile (text "RIFF....WEBPVP8 "))) "WebP")
      (is (= "light" (logos/logo-tile (byte-array (take 30 white-png)))) "a truncated PNG")
      (is (= "light" (logos/logo-tile (byte-array 0)))))))

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
        (is (contains? #{"light" "dark"} (:logo-tile logo)) "the tile shade is kept in the index")
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

(defn- cached-state!
  ([dir ns] (cached-state! dir ns png))
  ([dir ns body]
   (let [entry (logos/store! dir ns {:content-type "image/png" :body body :source "x"} nil "2026-11-17T10:00:00.000Z")]
     (swap! data/state assoc-in [:logos ns] entry)
     entry)))

(deftest api-exposes-logo-cached
  (let [flymine-own (get-in (first (filter #(= "flymine" (:namespace %)) (data/instances))) [:images :logo])
        dir (temp-dir)
        fly (fn [uri] (let [b (u/body-json (u/request :get uri {:handler-opts {:base-url "https://reg.example.org"}}))]
                        (or (get b "instance")
                            (first (filter #(= "flymine" (get % "namespace")) (get b "instances"))))))]
    (try
      (testing "nothing cached: no logo_cached or logo_tile, images.logo as before"
        (is (not (contains? (get (fly "/service/instances/flymine") "images") "logo_cached")))
        (is (not (contains? (get (fly "/service/instances/flymine") "images") "logo_tile")))
        (is (= flymine-own (get-in (fly "/service/instances/flymine") ["images" "logo"]))))
      (cached-state! dir "flymine")
      (testing "cached: absolute URL from REGISTRY_BASE_URL, images.logo untouched"
        (doseq [uri ["/service/instances/flymine" "/service/instances"]]
          (is (= "https://reg.example.org/instances/flymine/logo" (get-in (fly uri) ["images" "logo_cached"])) uri)
          (is (= flymine-own (get-in (fly uri) ["images" "logo"])) uri))
        (is (not (contains? (get (u/body-json (u/request :get "/service/instances/mousemine")) "instance") "logo_cached"))))
      (testing "images.logo_tile: the tile shade of the cached logo, only when there is one"
        (cached-state! dir "flymine" (encode (image 8 8 (constantly black)) "png"))
        (cached-state! dir "aquaMine" white-png)
        (let [insts (get (u/body-json (u/request :get "/service/instances")) "instances")
              tile (fn [ns] (get-in (first (filter #(= ns (get % "namespace")) insts)) ["images" "logo_tile"]))]
          (is (= "light" (tile "flymine")))
          (is (= "dark" (tile "aquaMine")))
          (is (= ["logo_cached" "logo_tile"] (take-last 2 (keys (get (first (filter #(= "aquaMine" (get % "namespace")) insts)) "images"))))
              "after the mine's own images keys")
          (is (not (contains? (get (first (filter #(= "mousemine" (get % "namespace")) insts)) "images") "logo_tile")))
          (is (= "dark" (get-in (u/body-json (u/request :get "/service/instances/aquamine")) ["instance" "images" "logo_tile"])))))
      (testing "an index entry without a shade (before it was kept) counts as light"
        (swap! data/state update-in [:logos "aquaMine"] dissoc :logo-tile)
        (is (= "light" (get-in (u/body-json (u/request :get "/service/instances/aquamine")) ["instance" "images" "logo_tile"]))))
      (finally (delete-tree! dir)))))

(deftest existing-caches-get-their-tiles-at-start-up
  (let [dir (temp-dir)
        black-png (encode (image 8 8 (constantly black)) "png")
        stored (fn [ns body] (dissoc (logos/store! dir ns {:content-type "image/png" :body body :source "x"} nil "2026-11-17T10:00:00.000Z")
                                     :logo-tile))]
    (try
      ;; an index written before :logo-tile was kept, plus an entry whose file is gone
      ;; and one with a bad value
      (spit (data/cache-file dir)
            (pr-str {:logos {"aquaMine" (stored "aquaMine" white-png)
                             "flymine" (stored "flymine" black-png)
                             "thalemine" (assoc (stored "thalemine" white-png) :logo-tile "purple")
                             "mousemine" {:file "mousemine.png" :content-type "image/png"}}}))
      (data/load! (assoc u/opts :cache-dir dir))
      (is (nil? (get-in @data/state [:logos "thalemine" :logo-tile])) "a bad value is dropped on load")
      (is (= ["aquaMine" "flymine" "thalemine"] (logos/add-missing-tiles! dir)))
      (is (= "dark" (get-in @data/state [:logos "aquaMine" :logo-tile])))
      (is (= "light" (get-in @data/state [:logos "flymine" :logo-tile])))
      (is (= "dark" (get-in @data/state [:logos "thalemine" :logo-tile])))
      (is (not (contains? (get-in @data/state [:logos "mousemine"]) :logo-tile)) "no file: left alone")
      (is (= "light" (logos/tile "mousemine")) "and shown on a light tile")
      (testing "written back to the cache"
        (is (= "dark" (get-in (data/read-cache dir) [:logos "aquaMine" :logo-tile])))
        (is (= "light" (get-in (data/read-cache dir) [:logos "flymine" :logo-tile]))))
      (testing "the next start has nothing to do"
        (data/load! (assoc u/opts :cache-dir dir))
        (is (= [] (logos/add-missing-tiles! dir))))
      (is (nil? (logos/add-missing-tiles! nil)) "no cache dir")
      (finally (delete-tree! dir)))))

(deftest pages-use-the-registry-copy
  (let [dir (temp-dir)
        flymine-own (get-in (first (filter #(= "flymine" (:namespace %)) (data/instances))) [:images :logo])
        page (fn [uri] (:body (u/request :get uri)))]
    (try
      (testing "before the first refresh: only the initial marks (and the logo overrides), never the mine's own host"
        (let [home (page "/")]
          (is (= ["<img alt=\"\" loading=\"lazy\" onerror=\"this.remove()\" src=\"/instances/planmine/logo\">"]
                 (re-seq #"<img[^>]*>" home))
              "only PlanMine's override")
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
      (testing "the tile shade follows the logo"
        (cached-state! dir "aquaMine" white-png)
        (let [home (page "/")
              aqua (page "/instances/aquaMine")]
          (is (str/includes? home "class=\"logo tile-dark\""))
          (is (str/includes? home "class=\"logo tile-light\""))
          (is (str/includes? aqua "class=\"logo tile-dark\"") "the instance page heading")
          (is (str/includes? aqua "class=\"big-logo tile-dark\"") "the Logo field")
          (is (str/includes? aqua "&quot;logo_tile&quot; : &quot;dark&quot;") "the JSON on the page")
          (is (str/includes? (page "/instances/mousemine") "class=\"logo tile-light\"") "no logo: the initial on a light tile")))
      (finally (delete-tree! dir)))))

;; ---------------------------------------------------------------- overrides

(def planmine-png
  "The bytes of the bundled override resources/public/logos/planmine.png."
  (with-open [in (io/input-stream (io/resource "public/logos/planmine.png"))] (.readAllBytes in)))

(defn- sha1 [^bytes b]
  (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest (java.security.MessageDigest/getInstance "SHA-1") b))))

(defn- api-instance [ns]
  (get (u/body-json (u/request :get (str "/service/instances/" ns) {:handler-opts {:base-url "https://reg.example.org"}}))
       "instance"))

(deftest overrides-are-read-from-the-classpath
  (is (= ["planmine"] (logos/load-overrides!)))
  (let [o (logos/override "planmine")]
    (is (= {:file "planmine.png" :content-type "image/png" :bytes (alength ^bytes planmine-png)
            :sha1 (sha1 planmine-png) :logo-tile "light" :override true}
           (dissoc o :body)))
    (is (= (vec planmine-png) (vec (:body o)))))
  (is (= "light" (logos/tile "planmine")) "the shade, computed from the image")
  (is (logos/has-logo? "planmine"))
  (is (not (logos/cached? "planmine")) "nothing fetched")
  (is (nil? (logos/override "flymine")))
  (is (not (logos/has-logo? "flymine")))
  (testing "a missing file is ignored (logged)"
    (swap! data/state update :instances conj (assoc testmine :url "http://example.invalid/testmine" :logo-override "nosuch.png"))
    (is (nil? (logos/override "testmine")))
    (is (= ["planmine"] (logos/load-overrides!)))
    (is (nil? (logos/override-resource "../instances.edn")) "never a path"))
  (testing "content types by extension"
    (is (= "image/svg+xml" (logos/override-content-types "svg")))
    (is (= "image/jpeg" (logos/override-content-types "jpeg")))
    (is (= "image/webp" (logos/override-content-types "webp")))))

(deftest the-override-is-served
  (doseq [dir-opts [{} {:cache-dir "/nonexistent/registry-cache"}]]
    (let [resp (u/request :get "/instances/planmine/logo" {:handler-opts dir-opts})]
      (is (= 200 (:status resp)) "with or without a cache dir")
      (is (= "image/png" (get-in resp [:headers "Content-Type"])))
      (is (= "public, max-age=86400" (get-in resp [:headers "Cache-Control"])))
      (is (= "nosniff" (get-in resp [:headers "X-Content-Type-Options"])))
      (is (= (str "\"" (sha1 planmine-png) "\"") (get-in resp [:headers "ETag"])) "ETag from the SHA-1, as for cached logos")
      (is (= (vec planmine-png) (vec (:body resp))))))
  (testing "If-None-Match: 304"
    (let [resp (u/request :get "/instances/planmine/logo" {:headers {"if-none-match" (str "\"" (sha1 planmine-png) "\"")}})]
      (is (= 304 (:status resp))))))

(deftest the-override-wins-over-a-cached-logo
  (let [dir (temp-dir)
        rawgit "https://cdn.rawgit.com/intermine/design-materials/78a13db5/logos/intermine/squareish/45x45.png"]
    (try
      (testing "API, nothing cached"
        (is (= {"logo" "https://reg.example.org/instances/planmine/logo"
                "logo_cached" "https://reg.example.org/instances/planmine/logo"
                "logo_tile" "light"
                "logo_declared" rawgit}
               (get (api-instance "planmine") "images"))))
      ;; a copy fetched before the override existed: white, so it would be on a dark tile
      (cached-state! dir "planmine" white-png)
      (is (= "dark" (get-in @data/state [:logos "planmine" :logo-tile])))
      (testing "API: the override's URL and shade, not the cached copy's"
        (let [images (get (api-instance "planmine") "images")]
          (is (= "https://reg.example.org/instances/planmine/logo" (get images "logo")))
          (is (= "https://reg.example.org/instances/planmine/logo" (get images "logo_cached")))
          (is (= "light" (get images "logo_tile")))
          (is (= rawgit (get images "logo_declared"))))
        (let [listed (first (filter #(= "planmine" (get % "namespace"))
                                    (get (u/body-json (u/request :get "/service/instances")) "instances")))]
          (is (= "https://registry.intermine.org/instances/planmine/logo" (get-in listed ["images" "logo"])))
          (is (= "light" (get-in listed ["images" "logo_tile"])))))
      (testing "the route: the override's bytes, not the cached file"
        (let [resp (u/request :get "/instances/planmine/logo" {:handler-opts {:cache-dir dir}})]
          (is (= (vec planmine-png) (vec (:body resp))))
          (is (= (str "\"" (sha1 planmine-png) "\"") (get-in resp [:headers "ETag"])))))
      (testing "the pages"
        (let [home (:body (u/request :get "/"))
              inst (:body (u/request :get "/instances/planmine"))]
          (is (re-find #"<span aria-hidden=\"true\" class=\"logo tile-light\"><span class=\"placeholder\">P</span><img [^>]*src=\"/instances/planmine/logo\"" home)
              "the light tile of the override, not the cached copy's dark one")
          (is (str/includes? inst "class=\"big-logo tile-light\""))
          (is (str/includes? inst "the registry's own image (<code>planmine.png</code>)") "the Logo field")
          (is (str/includes? inst rawgit) "the declared logo is still shown")
          (is (str/includes? inst "logo_declared") "in the JSON on the page")))
      (testing "an override without a declared logo: no logo_declared"
        (swap! data/state update :instances (fn [insts] (mapv #(if (= "planmine" (:namespace %)) (assoc % :images {}) %) insts)))
        (is (= #{"logo" "logo_cached" "logo_tile"} (set (keys (get (api-instance "planmine") "images"))))))
      (finally (delete-tree! dir)))))

(deftest the-refresh-skips-the-logo-of-an-overridden-mine
  (let [hits (atom 0)
        counted (fn [] (swap! hits inc) [200 "image/png" white-png])]
    (with-mine {"/testmine/model/images/logo.png" counted
                "/testmine/model/images/favicon.ico" counted
                "/testmine/favicon.ico" counted}
      (fn [dir _ _]
        (swap! data/state update :instances
               (fn [insts] (mapv #(if (= "testmine" (:namespace %)) (assoc % :logo-override "planmine.png") %) insts)))
        (let [[{:keys [logo errors instance]}] (refresh-testmine! dir)]
          (is (= "Running" (:status instance)) "the check itself ran")
          (is (= "ok" (get-in instance [:last_check :outcome])))
          (is (= {:logo "model/images/logo.png"} (:images instance)) "the branding is still refreshed")
          (is (= "planmine.png" (:logo-override instance)) "the override is kept")
          (is (nil? logo))
          (is (empty? errors) "no error for the skipped step")
          (is (zero? @hits) "no logo fetched")
          (is (nil? (get-in @data/state [:logos "testmine"])))
          (is (not (.exists (io/file dir "logos" "testmine.png")))))
        (is (= (vec planmine-png) (vec (:body (u/request :get "/instances/testmine/logo" {:handler-opts {:cache-dir dir}}))))
            "the override is served after the refresh")
        (is (= "planmine.png" (:logo-override (first (filter #(= "testmine" (:namespace %)) (data/instances)))))
            "and is still in the state")
        (testing "an override whose file is missing does not stop the fetch"
          (swap! data/state update :instances
                 (fn [insts] (mapv #(if (= "testmine" (:namespace %)) (assoc % :logo-override "nosuch.png") %) insts)))
          (let [[{:keys [logo]}] (refresh-testmine! dir)]
            (is (= "testmine.png" (:file logo)))
            (is (= 1 @hits))))))))
