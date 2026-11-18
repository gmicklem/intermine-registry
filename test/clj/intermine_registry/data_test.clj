(ns intermine-registry.data-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [intermine-registry.data :as data]
            [intermine-registry.test-util :as u])
  (:import [java.io File]))

(def bundled (edn/read-string (slurp (io/resource "instances.edn"))))

(defn- problems-of [instances]
  (try (data/validate! instances "test") nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

(deftest the-bundled-file-is-valid
  (is (= (count bundled) (count (data/read-instances nil))) "the file as read by the app has every entry")
  (is (pos? (count bundled)))
  (is (= u/seeded-count (count (data/read-instances nil))))
  (is (some #{"targetmine"} (map :namespace bundled)) "TargetMine, added 17 Nov 2026")
  (is (every? #(contains? % :namespace) bundled))
  (testing "every logo override names a file on the classpath, under public/logos/"
    (is (= #{"planmine" "targetmine"} (set (map :namespace (filter :logo-override bundled)))) "planmine and targetmine have one")
    (doseq [{:keys [namespace logo-override]} bundled
            :when logo-override]
      (is (some? (io/resource (str "public/logos/" logo-override))) (str namespace ": " logo-override)))))

(deftest logo-override-spec
  (let [planmine (first (filter #(= "planmine" (:namespace %)) bundled))
        with (fn [v] (problems-of (mapv #(if (= "planmine" (:namespace %)) (assoc % :logo-override v) %) bundled)))]
    (is (= "planmine.png" (:logo-override planmine)))
    (testing "accepted"
      (doseq [v ["planmine.png" "x.svg" "a_b-c.1.jpg" "Logo.jpeg" "l.gif" "l.webp"]]
        (is (nil? (with v)) v))
      (is (nil? (problems-of (mapv #(dissoc % :logo-override) bundled))) "optional"))
    (testing "rejected"
      (doseq [v ["../planmine.png" "logos/planmine.png" "/etc/passwd.png" "planmine" "planmine.ico"
                 "planmine.PNG" "plan mine.png" "planmine.png.exe" "" nil 42]]
        (let [msg (with v)]
          (is (some? msg) (pr-str v))
          (when msg (is (str/includes? msg ":logo-override") (pr-str v))))))))

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

(deftest normalize-instance-statuses
  (let [n data/normalize-instance]
    (testing "the seeded capture counts as an ok check at last_time_updated"
      (is (= {:status "Running" :last_time_updated "2026-11-17T00:00:32.310Z"
              :last_check {:time "2026-11-17T00:00:32.310Z" :outcome "ok" :reason nil :since nil}}
             (n {:status "Running" :last_time_updated "2026-11-17T00:00:32.310Z"}))))
    (testing "never checked"
      (is (= {:status "Not checked yet" :last_check nil} (n {})))
      (is (= "Not checked yet" (:status (n {:status "Running"}))) "no check time, no check"))
    (testing "legacy Not Running becomes Unreachable with a failed check"
      (let [r (n {:status "Not Running" :last_time_updated "2026-11-17T00:00:32.310Z"})]
        (is (= "Unreachable" (:status r)))
        (is (= "failed" (get-in r [:last_check :outcome])))
        (is (= "2026-11-17T00:00:32.310Z" (get-in r [:last_check :since])))))
    (testing "an existing last_check is kept"
      (let [i {:status "Unreachable" :last_check {:time "2026-11-17T00:00:32.310Z" :outcome "failed" :reason "timeout" :since "2026-11-16T00:00:00.000Z"}}]
        (is (= i (n i)))))))

(deftest loaded-instances-have-status-and-last-check
  (data/load! {})
  (let [seeded (remove #(= "targetmine" (:namespace %)) (data/instances))
        tm (first (filter #(= "targetmine" (:namespace %)) (data/instances)))]
    (is (seq seeded))
    (is (every? #(= "Running" (:status %)) seeded))
    (is (every? #(= "ok" (get-in % [:last_check :outcome])) seeded))
    (is (every? #(= (:last_time_updated %) (get-in % [:last_check :time])) seeded))
    (testing "TargetMine is listed as Unreachable, with the reason, until its checks succeed"
      (is (= "Unreachable" (:status tm)))
      (is (= {:time "2026-11-17T00:00:00Z" :outcome "failed"
              :reason "server being repaired (maintainer, November 2026)" :since "2026-11-17T00:00:00Z"}
             (:last_check tm)))
      (is (nil? (:last_time_updated tm)) "never checked by this registry"))))

(deftest cache-keeps-last-check-and-migrates-the-old-status
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "registry-cache" (make-array java.nio.file.attribute.FileAttribute 0)))
        check {:time "2026-11-18T10:00:00.000Z" :outcome "failed" :reason "timeout" :since "2026-11-18T09:00:00.000Z"}]
    (try
      (data/load! {})
      (swap! data/state update :instances (fn [is] (mapv #(case (:namespace %)
                                                            "flymine" (assoc % :status "Unreachable" :last_check check)
                                                            %) is)))
      (data/write-cache! dir @data/state)
      (data/load! {:cache-dir dir})
      (let [fly (first (filter #(= "flymine" (:namespace %)) (data/instances)))]
        (is (= "Unreachable" (:status fly)))
        (is (= check (:last_check fly)) "last_check survives a restart"))
      (testing "a cache written before last_check existed (status Not Running)"
        (spit (data/cache-file dir) (pr-str {:instances {"flymine" {:status "Not Running" :last_time_updated "2026-11-17T05:00:00.000Z"}}}))
        (data/load! {:cache-dir dir})
        (let [fly (first (filter #(= "flymine" (:namespace %)) (data/instances)))]
          (is (= "Unreachable" (:status fly)))
          (is (= "failed" (get-in fly [:last_check :outcome])))))
      (finally (doseq [f (reverse (file-seq dir))] (.delete ^File f))))))
