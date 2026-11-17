(ns intermine-registry.data-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [intermine-registry.data :as data])
  (:import [java.io File]))

(def bundled (edn/read-string (slurp (io/resource "instances.edn"))))

(defn- problems-of [instances]
  (try (data/validate! instances "test") nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

(deftest the-bundled-file-is-valid
  (is (= 14 (count (data/read-instances nil))))
  (is (every? #(contains? % :namespace) bundled)))

(deftest bad-files-are-refused-with-a-clear-message
  (let [flymine (first (filter #(= "flymine" (:namespace %)) bundled))]
    (testing "duplicate namespace (case-insensitive)"
      (is (str/includes? (problems-of (conj bundled (assoc flymine :id "999" :namespace "FlyMine")))
                         "duplicate namespaces")))
    (testing "duplicate id"
      (is (str/includes? (problems-of (conj bundled (assoc flymine :namespace "flymine2"))) "duplicate ids")))
    (testing "isProduction must be a boolean"
      (let [msg (problems-of (assoc bundled 0 (assoc (first bundled) :isProduction "yes")))]
        (is (str/includes? msg ":isProduction"))
        (is (str/includes? msg "alliancemine"))))
    (testing "url shape"
      (is (str/includes? (problems-of (assoc bundled 0 (assoc (first bundled) :url "ftp://x"))) ":url")))
    (testing "missing or empty namespace"
      (is (problems-of (assoc bundled 0 (dissoc (first bundled) :namespace))))
      (is (problems-of (assoc bundled 0 (assoc (first bundled) :namespace "")))))
    (testing "reserved namespace"
      (is (problems-of (assoc bundled 0 (assoc (first bundled) :namespace "docs")))))
    (testing "HTML in a refreshed field"
      (is (problems-of (assoc bundled 0 (assoc (first bundled) :api_version "<!DOCTYPE html>")))))
    (testing "not a vector, empty"
      (is (problems-of {}))
      (is (problems-of [])))))

(deftest file-errors
  (let [f (File/createTempFile "instances" ".edn")]
    (try
      (spit f "[{:id \"1\"")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not valid EDN" (data/read-instances (str f))))
      (spit f "[{:id \"1\"}]")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid instances file" (data/read-instances (str f))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found" (data/read-instances "/nonexistent/x.edn")))
      (finally (.delete f)))))

(deftest cache-round-trip
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "registry-cache" (make-array java.nio.file.attribute.FileAttribute 0)))
        insts (data/read-instances nil)
        changed (assoc-in insts [3 :release_version] "v54")
        changed (assoc-in changed [4 :api_version] "<html>")]   ; invalid: ignored on load
    (try
      (is (data/write-cache! dir {:instances changed :refresh {:last-run {:finished "x"}}}))
      (let [merged (data/merge-cache insts (data/read-cache dir))]
        (is (= "v54" (get-in merged [3 :release_version])))
        (is (= (get-in insts [4 :api_version]) (get-in merged [4 :api_version])) "invalid cached value ignored"))
      (finally
        (doseq [f (reverse (file-seq dir))] (.delete ^File f))))))

(deftest time-formats
  (is (re-matches #"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z" (data/iso-now)))
  (is (= "2026-11-17T00:00:00.000Z" (data/iso-now (java.time.Instant/parse "2026-11-17T00:00:00Z")))))
