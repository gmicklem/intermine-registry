(ns intermine-registry.map-test
  "The world map page (/map) and its land outline."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

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
