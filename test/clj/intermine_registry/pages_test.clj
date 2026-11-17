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
  (let [resp (u/request :get "/css/registry.css")
        css (slurp (:body resp))]
    (is (= 200 (:status resp)))
    (is (str/starts-with? (get-in resp [:headers "Content-Type"]) "text/css"))
    (testing "the dark palette for the OS preference unless Light was chosen, and for an explicit Dark"
      (is (str/includes? css ":root:not([data-theme=\"light\"])"))
      (is (str/includes? css ":root[data-theme=\"dark\"]"))
      (is (= 2 (count (re-seq #"(?m)^\s*color-scheme: dark;" css)))))
    (is (str/includes? css ".tile-dark"))
    (is (str/includes? css ".tile-light"))))

(deftest theme-switch
  (doseq [uri ["/" "/map" "/docs" "/status" "/instances/flymine"]]
    (let [body (page uri)
          head (subs body 0 (str/index-of body "</head>"))]
      (testing uri
        (is (str/includes? body "id=\"theme-switch\""))
        (doseq [v ["system" "light" "dark"]]
          (is (str/includes? body (str "id=\"theme-" v "\" name=\"theme\" type=\"radio\" value=\"" v "\"")) v))
        (is (str/includes? body "hidden") "hidden until the script shows it (no JavaScript: the OS decides)")
        (is (str/includes? head "localStorage.getItem('registry-theme')") "applied in <head>, before the first paint")
        (is (< (str/index-of head "registry-theme") (str/index-of head "registry.css")) "before the stylesheet")
        (is (str/includes? body "localStorage.setItem('registry-theme'"))))))

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

(defn- make-unreachable! [ns reason]
  (swap! intermine-registry.data/state update :instances
         (fn [insts] (mapv #(if (= ns (:namespace %))
                              (assoc % :status "Unreachable"
                                     :last_check {:time "2026-11-18T10:00:00.000Z" :outcome "failed"
                                                  :reason reason :since "2026-11-17T09:00:00.000Z"})
                              %)
                           insts))))

(deftest unreachable-mine-shows-the-reason
  (make-unreachable! "flymine" "bot-protection page")
  (let [home (page "/")
        inst (page "/instances/flymine")
        status (page "/status")]
    (is (str/includes? home "FlyMine") "still on the home page")
    (is (str/includes? home "Unreachable since 2026-11-17 (bot-protection page)"))
    (is (str/includes? home "class=\"dot unreachable\""))
    (is (str/includes? inst "Last check"))
    (is (str/includes? inst "bot-protection page"))
    (is (str/includes? inst "2026-11-18 10:00 UTC"))
    (is (str/includes? inst "For the maintainer of this mine"))
    (is (str/includes? inst "README.md#for-mine-maintainers-letting-the-registry-check-your-mine"))
    (is (str/includes? status "Unreachable since 2026-11-17 (bot-protection page)")))
  (testing "other reasons: no bot-protection hint, still a pointer"
    (make-unreachable! "flymine" "timeout")
    (let [inst (page "/instances/flymine")]
      (is (str/includes? inst "timeout"))
      (is (not (str/includes? inst "Anubis")))
      (is (str/includes? inst "what the registry's checks need"))))
  (testing "a healthy mine has no notice"
    (u/load-bundled!)
    (let [inst (page "/instances/mousemine")]
      (is (str/includes? inst "Last check"))
      (is (not (str/includes? inst "For the maintainer")))
      (is (not (str/includes? (page "/") "Unreachable since"))))))

(deftest a-failing-mine-still-running-shows-a-note
  (swap! intermine-registry.data/state update :instances
         (fn [insts] (mapv #(if (= "flymine" (:namespace %))
                              (assoc % :last_check {:time "2026-11-18T10:00:00.000Z" :outcome "failed"
                                                    :reason "http 503" :since "2026-11-17T22:00:00.000Z"})
                              %)
                           insts)))
  (let [inst (page "/instances/flymine")
        home (page "/")]
    (is (str/includes? inst "Running; check failed (http 503), retrying"))
    (is (str/includes? inst "Failing since"))
    (is (str/includes? inst "2026-11-17 22:00 UTC"))
    (is (str/includes? inst "more than 24 hours"))
    (is (str/includes? home "Running; check failed (http 503), retrying") "the note on the home page")
    (is (str/includes? home "class=\"dot up\""))
    (is (not (str/includes? home "Unreachable since"))))
  (testing "REGISTRY_UNREACHABLE_AFTER_HOURS is the number on the page"
    (let [resp (u/request :get "/instances/flymine" {:handler-opts {:unreachable-after-hours 48}})]
      (is (str/includes? (:body resp) "more than 48 hours")))))

(deftest status-page-states-the-schedule
  (let [resp (u/request :get "/status" {:handler-opts {:minutes 360}})]
    (is (str/includes? (:body resp) "every 6 hours"))
    (is (str/includes? (:body resp) "more than 24 hours"))
    (is (not (str/includes? (:body resp) "two failed")))))
