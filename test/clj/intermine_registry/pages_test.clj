(ns intermine-registry.pages-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [intermine-registry.test-util :as u]))

(use-fixtures :each u/with-bundled-data)

(def all-names ["AllianceMine" "AquaMine" "FAANGMine" "FlyMine" "HumanMine" "HymenopteraMine" "MaizeMine"
                "MouseMine" "OakMine" "PhytoMine" "PlanMine" "RepetDB" "ThaleMine" "WheatMine"])

(defn- page [uri & [query]]
  (let [resp (u/request :get uri {:query query})]
    (is (= 200 (:status resp)) uri)
    (is (str/starts-with? (get-in resp [:headers "Content-Type"]) "text/html") uri)
    (:body resp)))

(deftest home
  (let [body (page "/")]
    (doseq [n all-names] (is (str/includes? body n) n))
    (is (str/includes? body "id=\"filter\""))
    (is (str/includes? body "/?mines=all"))
    (is (str/includes? body "Leonardo Kuffo"))
    (is (str/includes? body "/css/registry.css")))
  (is (str/includes? (page "/" "mines=all") "show production mines only")))

(deftest instance-detail
  (let [body (page "/instances/flymine")]
    (is (str/includes? body "FlyMine"))
    (is (str/includes? body "https://www.flymine.org/flymine/service"))
    (is (str/includes? body "/service/instances/flymine"))
    (is (str/includes? body "#5c0075") "colour swatch"))
  (is (= 404 (:status (u/request :get "/instances/nosuchmine")))))

(deftest docs-and-status
  (let [body (page "/docs")]
    (doseq [p ["/service/instances" "/service/instances/{id}" "/service/namespace" "/service/synchronize" "/health"]]
      (is (str/includes? body p) p)))
  (let [body (page "/status")]
    (is (str/includes? body "FlyMine"))))

(deftest stylesheet
  (let [resp (u/request :get "/css/registry.css")]
    (is (= 200 (:status resp)))
    (is (str/starts-with? (get-in resp [:headers "Content-Type"]) "text/css"))))

(deftest text-is-escaped
  (swap! intermine-registry.data/state update :instances conj
         {:id "999" :name "<script>alert(1)</script>" :namespace "evil" :url "javascript:alert(1)"
          :description "" :organisms ["<b>x</b>"] :neighbours [] :location {:latitude "" :longitude ""}
          :maintainerOrgName "" :maintainerUrl "" :maintainerEmail "" :maintainerGithubUrl "" :twitter ""
          :isProduction true :created_at nil :status "Running" :images {:logo "javascript:alert(2)"}})
  (let [body (page "/")]
    (is (not (str/includes? body "<script>alert(1)")))
    (is (not (str/includes? body "javascript:alert")))
    (is (not (str/includes? body "<b>x</b>")))))

(deftest galaxy-pages
  (let [body (page "/im-to-galaxy" "GALAXY_URL=https://usegalaxy.org/tool_runner?tool_id=intermine")]
    (is (str/includes? body "https://www.flymine.org/flymine/begin.do?GALAXY_URL=https%3A%2F%2Fusegalaxy.org%2Ftool_runner%3Ftool_id%3Dintermine")))
  (let [body (page "/galaxy-to-im" "URL=https://usegalaxy.org/datasets/1/display")]
    (is (str/includes? body "https://www.flymine.org/flymine/portal.do"))
    (is (str/includes? body "Send to FlyMine"))
    (is (str/includes? body "\"https://usegalaxy.org/datasets/1/display\""))))
