(ns intermine-registry.test-runner
  "Runs the tests: clojure -M:test"
  (:require [clojure.test :as t]))

(def test-namespaces
  '[intermine-registry.data-test
    intermine-registry.api-test
    intermine-registry.refresh-test
    intermine-registry.logos-test
    intermine-registry.pages-test])

(defn -main [& _]
  (apply require test-namespaces)
  (let [{:keys [fail error]} (apply t/run-tests test-namespaces)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
