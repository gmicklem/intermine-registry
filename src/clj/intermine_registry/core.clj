(ns intermine-registry.core
  "Entry point (java -jar, clojure -M -m intermine-registry.core).

  The only AOT-compiled namespace (build.clj); it loads
  intermine-registry.system at run time, so no class of another library is
  compiled into the jar (an AOT-compiled call to a tools.logging macro, for
  instance, names a protocol class that only exists once the library is
  loaded from source)."
  (:gen-class))

(defn -main [& args]
  (require 'intermine-registry.system)
  (apply (resolve 'intermine-registry.system/-main) args))
