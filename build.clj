(ns build
  "tools.build tasks for the InterMine Registry.

    clojure -T:build clean   ; delete target/
    clojure -T:build uber    ; target/intermine-registry.jar (java -jar ...)

  Like BlueGenes' build.clj, only the main namespace is AOT-compiled; the
  rest loads from source at start-up, which keeps the jar portable across
  JDKs (17 and later)."
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/intermine-registry.jar")

;; :user nil: the result must not depend on the builder's ~/.clojure/deps.edn.
(defn- basis [] (b/create-basis {:project "deps.edn" :user nil}))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (let [basis (basis)]
    (b/copy-dir {:src-dirs ["src/clj" "resources"] :target-dir class-dir})
    (b/compile-clj {:basis basis
                    :ns-compile '[intermine-registry.core]
                    :filter-nses '[intermine-registry.core]
                    :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis basis
             :main 'intermine-registry.core})
    (println "Created" uber-file)))
