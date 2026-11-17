# Contributing to the InterMine Registry

The InterMine Registry is an open source project that welcomes contributions from the community.

- **Adding a mine or changing its details**: edit `resources/instances.edn` (the comment at the top
  of the file describes the fields) and open a pull request. `clojure -M:test` checks the file.
- **Code**: fork the repository, branch from `dev`, and open a pull request. Before pushing, run
  `clojure -M:fmt check` and `clojure -M:test`; CI also builds the uberjar and the Docker image.
  The read API is used by BlueGenes, the InterMine webapps, client libraries and Galaxy: keep its
  answers compatible (the tests compare them with answers captured from the original registry).
