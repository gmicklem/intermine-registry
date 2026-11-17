(ns intermine-registry.api-test
  "Compatibility with the Node registry: the answers are compared, field by
  field, with the live answers captured on 17 Nov 2026
  (test/resources/capture-2026-11-17)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [intermine-registry.api :as api]
            [intermine-registry.test-util :as u]))

(use-fixtures :each u/with-bundled-data)

(def dropped-keys
  "Mongo's internal keys, deliberately not served any more (CHANGELOG)."
  #{"_id" "__v"})

(def cleaned
  "Values that were HTML pages in the capture and are \"\" in instances.edn
  (namespace -> keys)."
  {"alliancemine" #{"api_version"}
   "oakmine" #{"api_version" "intermine_version" "release_version"}
   "phytomine" #{"api_version" "intermine_version" "release_version"}
   "repetdbmine" #{"api_version" "intermine_version" "release_version"}
   "wheatmine" #{"api_version" "intermine_version" "release_version"}})

(def missouri
  "The Elsik Lab, University of Missouri (HymenopteraMine's and MaizeMine's
  location in the capture)."
  {"latitude" "38.940397" "longitude" "-92.327813"})

(def corrected
  "URLs that were wrong or stale in the capture, and locations that were
  blank, corrected in instances.edn (namespace -> key -> value), see the
  CHANGELOG."
  {"alliancemine" {"url" "https://alliancemine.alliancegenome.org/alliancemine"
                   "location" {"latitude" "37.428265" "longitude" "-122.169498"}}
   "aquaMine" {"location" missouri}
   "faangmine" {"location" missouri}
   "oakmine" {"url" "https://urgi.versailles.inrae.fr/OakMine_PM1N"
              "maintainerUrl" "https://urgi.versailles.inrae.fr/"}
   "repetdbmine" {"url" "https://urgi.versailles.inrae.fr/repetdb"
                  "maintainerUrl" "https://urgi.versailles.inrae.fr/"}
   "wheatmine" {"url" "https://urgi.versailles.inrae.fr/WheatMine"
                "maintainerUrl" "https://urgi.versailles.inrae.fr/"}})

(def registry-logo-url
  "The registry's URL of a mine's logo as the tests build it (u/opts
  :base-url, as REGISTRY_BASE_URL, then /instances/{namespace}/logo)."
  #(str (:base-url u/opts) "/instances/" % "/logo"))

(def corrected-images
  "images keys that differ from the capture because the registry carries a
  logo override for the mine (:logo-override in instances.edn): namespace ->
  key -> value, merged into the captured images (the captured images.logo
  moves to images.logo_declared)."
  {"planmine" {"logo" (registry-logo-url "planmine")
               "logo_cached" (registry-logo-url "planmine")
               "logo_tile" "light"
               "logo_declared" "https://cdn.rawgit.com/intermine/design-materials/78a13db5/logos/intermine/squareish/45x45.png"}})

(defn- expected-instance [captured]
  (let [ns (get captured "namespace")]
    (cond-> (merge (reduce (fn [m k] (assoc m k ""))
                           (apply dissoc captured dropped-keys)
                           (cleaned ns))
                   (corrected ns))
      (corrected-images ns) (update "images" merge (corrected-images ns)))))

(deftest instances-envelope-and-fields
  (let [captured (u/capture "instances.json")
        resp (u/request :get "/service/instances")
        ours (u/body-json resp)]
    (is (= 200 (:status resp)))
    (is (= "application/json; charset=utf-8" (get-in resp [:headers "Content-Type"])))
    (is (= "*" (get-in resp [:headers "Access-Control-Allow-Origin"])))
    (testing "same envelope keys"
      (is (= (set (keys captured)) (set (keys ours)))))
    (is (= 200 (get ours "statusCode")))
    (is (re-matches #"\d{1,2}/\d{1,2}/\d{4}, \d{1,2}:\d\d:\d\d [AP]M" (get ours "executionTime"))
        "executionTime in the Node app's toLocaleString format")
    (testing "same instances, same order (sorted by name)"
      (is (= (map #(get % "namespace") (get captured "instances"))
             (map #(get % "namespace") (get ours "instances")))))
    (doseq [[c o] (map vector (get captured "instances") (get ours "instances"))
            :let [ns (get c "namespace")]]
      (testing ns
        (is (= (set (remove dropped-keys (keys c))) (disj (set (keys o)) "last_check"))
            "same key set (minus _id, __v; plus last_check)")
        (doseq [k (keys (expected-instance c))]
          (is (= (get (expected-instance c) k) (get o k)) (str ns " " k)))
        (is (string? (get o "id")))))))

(deftest mines-and-q-params
  (let [names #(map (fn [i] (get i "name")) (get (u/body-json (u/request :get "/service/instances" {:query %})) "instances"))]
    (is (= 14 (count (names "mines=all"))))
    (is (= 14 (count (names "mines=prod"))))
    (is (empty? (names "mines=dev")) "the 14 seeded instances are all production")
    (is (= ["FlyMine"] (names "q=D.%20melanogaster")) "organism, exact")
    (is (= ["FlyMine"] (names "q=flym")) "part of the name, case-insensitive")
    (is (some #{"MouseMine"} (names "q=mouse")))
    (testing "trailing slash"
      (is (= 14 (count (get (u/body-json (u/request :get "/service/instances/")) "instances")))))))

(deftest one-instance
  (let [captured (u/capture "instance-flymine.json")
        ours (u/body-json (u/request :get "/service/instances/flymine"))]
    (is (= (set (keys captured)) (set (keys ours))))
    (is (= (expected-instance (get captured "instance")) (dissoc (get ours "instance") "last_check")))
    (is (= {"time" (get-in captured ["instance" "last_time_updated"]) "outcome" "ok" "reason" nil "since" nil}
           (get-in ours ["instance" "last_check"]))
        "the seeded capture counts as an ok check at its last_time_updated")
    (testing "by id, by name, case-insensitive"
      (is (= "flymine" (get-in (u/body-json (u/request :get "/service/instances/35")) ["instance" "namespace"])))
      (is (= "flymine" (get-in (u/body-json (u/request :get "/service/instances/FlyMine")) ["instance" "namespace"])))
      (is (= "aquaMine" (get-in (u/body-json (u/request :get "/service/instances/aquamine")) ["instance" "namespace"]))))
    (testing "404 like the Node app (errorMsg), plus a message"
      (let [resp (u/request :get "/service/instances/nosuchmine")
            body (u/body-json resp)]
        (is (= 404 (:status resp)))
        (is (= 404 (get body "statusCode")))
        (is (= "Not Found" (get body "errorMsg")))
        (is (string? (get body "message")))
        (is (not (contains? body "instance")))))))

(deftest namespace-lookup
  (let [captured (u/capture "namespace-flymine.json")
        ours (u/body-json (u/request :get "/service/namespace" {:query "url=https://www.flymine.org/flymine"}))]
    (is (= (set (keys captured)) (set (keys ours))))
    (is (= "flymine.beta" (get captured "namespace")) "the Node app's answer (substring match, first in the collection)")
    (is (= "flymine" (get ours "namespace")) "ours: the exact match")
    (testing "variants of the URL"
      (doseq [url ["www.flymine.org/flymine" "http://flymine.org/flymine/" "https://WWW.FlyMine.org/flymine"
                   "https://www.humanmine.org/humanmine"]]
        (is (= 200 (:status (u/request :get "/service/namespace" {:query (str "url=" url)}))) url)))
    (testing "exact match beats an earlier substring match (the flymine.beta case)"
      (let [beta {:namespace "flymine.beta" :url "https://beta.flymine.org/flymine" :isProduction false}
            prod {:namespace "flymine" :url "https://www.flymine.org/flymine" :isProduction true}]
        (is (= "flymine" (:namespace (api/find-by-url [beta prod] "www.flymine.org/flymine"))))
        (is (= "flymine" (:namespace (api/find-by-url [beta prod] "flymine.org"))) "substring: production first")
        (is (= "flymine.beta" (:namespace (api/find-by-url [beta prod] "beta.flymine.org/flymine"))))))
    (is (= 400 (:status (u/request :get "/service/namespace"))))
    (is (= "Url Not Found" (get (u/body-json (u/request :get "/service/namespace" {:query "url=example.org/nomine"})) "message")))))

(deftest namespace-redirect
  (let [resp (u/request :get "/flymine")]
    (is (= 303 (:status resp)))
    (is (= "https://www.flymine.org/flymine" (get-in resp [:headers "Location"]))))
  (let [resp (u/request :get "/nosuchmine")]
    (is (= 404 (:status resp)))
    (is (= "Namespace Not Found" (get (u/body-json resp) "message")))))

(deftest write-endpoints-are-gone
  (doseq [[method uri] [[:post "/service/instances"] [:post "/service/instances/"]
                        [:put "/service/instances/35"] [:delete "/service/instances/35"]]]
    (let [resp (u/request method uri)
          body (u/body-json resp)]
      (is (= 405 (:status resp)) (str method " " uri))
      (is (= 405 (get body "statusCode")))
      (is (str/includes? (get body "message") "pull request"))
      (is (= "GET" (get-in resp [:headers "Allow"]))))))

(deftest synchronize-needs-a-token
  (testing "no token configured: 405 with an explanation"
    (let [resp (u/request :put "/service/synchronize")]
      (is (= 405 (:status resp)))
      (is (str/includes? (get (u/body-json resp) "message") "REGISTRY_ADMIN_TOKEN"))))
  (testing "token configured, missing or wrong: 401"
    (is (= 401 (:status (u/request :put "/service/synchronize/flymine" {:handler-opts {:admin-token "s3cret"}}))))
    (is (= 401 (:status (u/request :put "/service/synchronize"
                                   {:handler-opts {:admin-token "s3cret"}
                                    :headers {"authorization" "Bearer wrong"}})))))
  (testing "unknown instance with the right token: 404"
    (is (= 404 (:status (u/request :put "/service/synchronize/nosuchmine"
                                   {:handler-opts {:admin-token "s3cret"}
                                    :headers {"authorization" "Bearer s3cret"}}))))))

(deftest health-and-docs-redirects
  (let [resp (u/request :get "/health")
        body (u/body-json resp)]
    (is (= 200 (:status resp)))
    (is (= "ok" (get body "status")))
    (is (= 14 (get body "instances")))
    (is (contains? body "lastRefresh")))
  (is (= 301 (:status (u/request :get "/api-docs"))))
  (is (= 302 (:status (u/request :get "/service"))))
  (testing "CORS preflight"
    (is (= 204 (:status (u/request :options "/service/instances"))))))

(defn- make-unreachable! [ns reason]
  (swap! intermine-registry.data/state update :instances
         (fn [insts] (mapv #(if (= ns (:namespace %))
                              (assoc % :status "Unreachable"
                                     :last_check {:time "2026-11-18T10:00:00.000Z" :outcome "failed"
                                                  :reason reason :since "2026-11-17T09:00:00.000Z"})
                              %)
                           insts))))

(deftest unreachable-mines-stay-listed
  (make-unreachable! "flymine" "bot-protection page")
  (let [insts (get (u/body-json (u/request :get "/service/instances")) "instances")
        fly (first (filter #(= "flymine" (get % "namespace")) insts))]
    (is (= 14 (count insts)) "still listed by default")
    (is (= "Unreachable" (get fly "status")))
    (is (= {"time" "2026-11-18T10:00:00.000Z" "outcome" "failed" "reason" "bot-protection page"
            "since" "2026-11-17T09:00:00.000Z"}
           (get fly "last_check")))
    (is (= (get-in (u/capture "instance-flymine.json") ["instance" "api_version"]) (get fly "api_version"))))
  (is (= 14 (count (get (u/body-json (u/request :get "/service/instances" {:query "mines=all"})) "instances"))))
  (is (= "Unreachable" (get-in (u/body-json (u/request :get "/service/instances/flymine")) ["instance" "status"])))
  (let [h (u/body-json (u/request :get "/health"))]
    (is (= 13 (get h "running")))
    (is (= 1 (get h "unreachable")))))
