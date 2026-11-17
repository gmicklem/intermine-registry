(ns intermine-registry.map-test
  "The world map page (/map) and its land outline."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [intermine-registry.data :as data]
            [intermine-registry.pages :as pages]
            [intermine-registry.test-util :as u]))

(use-fixtures :each u/with-bundled-data)

(deftest the-land-outline-is-a-plain-svg-path
  (let [res (io/resource "public/world-110m-land.path")
        d (str/trim (slurp res))]
    (is (some? res) "on the classpath")
    (is (str/starts-with? d "M"))
    (is (re-matches #"[MLZ0-9 .\-]+" d) "only M, L, Z and numbers")
    (is (< (count (.getBytes d "UTF-8")) (* 120 1024)) "under 120 KB")
    (testing "every number is within the 720 x 360 canvas"
      (let [nums (map parse-double (re-seq #"-?[0-9.]+" d))]
        (is (even? (count nums)))
        (is (every? #(<= 0 % 720) (take-nth 2 nums)))
        (is (every? #(<= 0 % 360) (take-nth 2 (rest nums))))))
    (is (> (count (re-seq #"M" d)) 50) "many rings (continents and islands)")))

(deftest projection
  (is (= [360.0 180.0] (pages/project 0 0)))
  (is (= [0.0 0.0] (pages/project 90 -180)))
  (is (= [720.0 360.0] (pages/project -90 180)))
  (is (= [360.24456 75.601528] (mapv #(/ (Math/round (* 1e6 %)) 1e6) (pages/project 52.199236 0.122280)))
      "FlyMine, Cambridge")
  (testing "coordinates from the strings of instances.edn"
    (is (= [52.199236 0.12228] (pages/coordinates {:location {:latitude "52.199236" :longitude "0.122280"}})))
    (is (nil? (pages/coordinates {:location {:latitude "" :longitude ""}})))
    (is (nil? (pages/coordinates {:location {:latitude "abc" :longitude "1"}})))
    (is (nil? (pages/coordinates {:location {:latitude "91" :longitude "0"}})) "not a latitude")
    (is (nil? (pages/coordinates {})))))

(defn- page [query]
  (let [resp (u/request :get "/map" {:query query})]
    (is (= 200 (:status resp)))
    (is (str/starts-with? (get-in resp [:headers "Content-Type"]) "text/html"))
    (:body resp)))

(def ^:private dot-re
  ;; a dot: a link to the mine's page, its name as <title>, then the circle
  #"<a [^>]*href=\"/instances/([^\"]+)\"[^>]*><title>([^<]+)</title><circle cx=\"([0-9.]+)\" cy=\"([0-9.]+)\"")

(defn- dots [body] (map (fn [[_ ns nm x y]] {:ns ns :name nm :x (parse-double x) :y (parse-double y)}) (re-seq dot-re body)))

(defn- not-on-map [body]
  (when-let [i (str/index-of body "id=\"not-on-map\"")]
    (subs body i (str/index-of body "</section>" i))))

(def ^:private injected
  {:id "998" :name "NowhereMine" :namespace "nowheremine" :url "https://example.org/nowheremine"
   :description "" :organisms [] :neighbours [] :location {:latitude "" :longitude ""}
   :maintainerOrgName "" :maintainerUrl "" :maintainerEmail "" :maintainerGithubUrl "" :twitter ""
   :isProduction true :created_at nil :status "Running"})

(deftest map-page
  (let [body (page nil)
        ds (dots body)
        prod (filter :isProduction (data/instances))
        n (count prod)]
    (is (str/includes? body "Where the mines are"))
    (is (str/includes? body "<a class=\"active\" href=\"/map\">Map</a>") "in the header nav, active")
    (is (str/includes? body "viewBox=\"0 0 720 360\""))
    (is (str/includes? body "class=\"land\" d=\"M") "the land outline, inline")
    (is (= u/seeded-count n) "the bundled mines are all production")
    (is (= n (count (re-seq #"<circle" body))) "a dot per production mine")
    (is (= n (count ds)) "each inside a link to the mine's page")
    (is (= (set (map :namespace prod)) (set (map :ns ds))))
    (is (= (set (map :name prod)) (set (map :name ds))) "named by its <title>")
    (testing "mines at one place are fanned out, not stacked"
      (is (= n (count (set (map (juxt :x :y) ds)))))
      (is (every? (fn [[a b]] (>= (Math/hypot (- (:x a) (:x b)) (- (:y a) (:y b))) 6.5))
                  (for [a ds b ds :when (neg? (compare (:ns a) (:ns b)))] [a b]))))
    (testing "a label per mine, in one block per cluster"
      (doseq [n (map :name prod)]
        (is (re-find (re-pattern (str "<text [^>]*>" n "</text>")) body) n)))
    (is (nil? (not-on-map body)) "every bundled mine has coordinates")
    (is (str/includes? body "resources/instances.edn") "how to change the coordinates")
    (is (not (str/includes? body "foreignObject")))
    (is (str/includes? body "/map?mines=all")))
  (testing "?mines=all"
    (let [body (page "mines=all")]
      (is (= (count (data/instances)) (count (dots body))))
      (is (str/includes? body "Show production mines only")))))

(deftest targetmine-is-drawn-alone-at-osaka
  (let [ds (dots (page nil))
        tm (first (filter #(= "targetmine" (:ns %)) ds))
        [x y] (pages/project 34.8225 135.5240)]
    (is (some? tm) "an Unreachable mine with coordinates still has its dot")
    (is (= "TargetMine" (:name tm)))
    (testing "no other mine is near: not fanned out with a cluster, at its own place"
      (is (< (Math/hypot (- x (:x tm)) (- y (:y tm))) 0.1) "the circle is at the projected position (rounded)")
      (is (every? #(> (Math/hypot (- (:x tm) (:x %)) (- (:y tm) (:y %))) 20.0)
                  (remove #(= "targetmine" (:ns %)) ds))))))

(deftest non-production-mines-only-with-mines-all
  (swap! data/state update :instances conj
         (assoc injected :id "997" :name "BetaMine" :namespace "betamine" :isProduction false
                :location {:latitude "-33.8688" :longitude "151.2093"}))
  (is (not-any? #(= "betamine" (:ns %)) (dots (page nil))))
  (is (some #(= "betamine" (:ns %)) (dots (page "mines=all"))))
  (is (str/includes? (:body (u/request :get "/instances/betamine")) "href=\"/map?mines=all#mine-betamine\"")))

(deftest a-mine-without-coordinates-is-listed-not-drawn
  (swap! data/state update :instances conj injected)
  (let [body (page nil)
        listed (not-on-map body)]
    (is (= (count (filter :isProduction (data/instances)))
           (inc (count (dots body))))
        "every production mine but NowhereMine has a dot")
    (is (not-any? #(= "nowheremine" (:ns %)) (dots body)))
    (is (some? listed))
    (is (str/includes? listed "Not on the map"))
    (is (str/includes? listed "<a href=\"/instances/nowheremine\">NowhereMine</a>"))))

(deftest the-instance-page-links-to-the-map
  (let [body (:body (u/request :get "/instances/flymine"))]
    (is (str/includes? body "52.199236, 0.122280"))
    (is (str/includes? body "<a href=\"/map#mine-flymine\">show on map</a>")))
  (testing "no link without coordinates"
    (swap! data/state update :instances conj injected)
    (is (not (str/includes? (:body (u/request :get "/instances/nowheremine")) "show on map")))))
