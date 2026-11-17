(ns intermine-registry.api
  "The registry's read API, compatible with the Node app's (routes/
  instances.js, routes/index.js, routes/synchronize.js of the GSoC 2017
  code), and the route table the /docs page is generated from."
  (:require [cheshire.core :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [intermine-registry.data :as data]
            [intermine-registry.logos :as logos]
            [intermine-registry.refresh :as refresh]
            [ring.util.http-response :as resp]))

(def repository-url "https://github.com/intermine/intermine-registry")

;; ---------------------------------------------------------------- JSON

(def json-key-order
  "The key order of the Node app's answers (Mongo document order)."
  [:location :colors :images :neighbours :organisms :id :name :url :created_at
   :last_time_updated :status :last_check :isProduction :twitter :description :api_version
   :release_version :intermine_version :maintainerOrgName :maintainerUrl
   :maintainerEmail :maintainerGithubUrl :namespace])

(def default-base-url "https://registry.intermine.org")

(defn instance->json
  "The instance as the API shows it: the keys of json-key-order that the
  instance has, in that order (Mongo's _id and __v are not part of it).
  With opts (:base-url), an instance whose logo the registry holds also has
  images.logo_cached, the absolute URL of the registry's copy; images.logo,
  the mine's own URL, is not changed."
  ([inst]
   (apply array-map (mapcat (fn [k] (when (contains? inst k) [k (get inst k)])) json-key-order)))
  ([inst {:keys [base-url]}]
   (let [j (instance->json inst)]
     (if (logos/cached? (:namespace inst))
       (assoc j :images (assoc (or (:images inst) (array-map))
                               :logo_cached (str (or base-url default-base-url) (logos/logo-path (:namespace inst)))))
       j))))

(defn json-response
  ([status body] (json-response status body {}))
  ([status body headers]
   {:status status
    :headers (merge {"Content-Type" "application/json; charset=utf-8"} headers)
    :body (json/generate-string body)}))

(defn- envelope
  "A map with the given entries in order, then statusCode and executionTime
  (where the Node app put them)."
  [status & kvs]
  (apply array-map (concat kvs [:statusCode status :executionTime (data/execution-time)])))

;; ---------------------------------------------------------------- queries

(defn- words [s] (set (re-seq #"[\p{L}\p{N}]+" (str/lower-case (str s)))))

(defn matches-q?
  "The Node app's ?q= search: q is one of the organisms, or a word of q is a
  word of the description (Mongo $text), or q occurs in the name
  (case-insensitive; the Node code used q as a regular expression, here it
  is a plain substring)."
  [q inst]
  (let [ql (str/lower-case q)]
    (or (some #(= q %) (:organisms inst))
        (boolean (seq (set/intersection (words q) (words (:description inst)))))
        (str/includes? (str/lower-case (:name inst)) ql))))

(defn- production-filter
  "?mines=dev: only non-production; prod: only production; any other value
  (all): every instance; absent: only production (as the Node app)."
  [mines]
  (cond (nil? mines) :isProduction
        (= "dev" mines) (complement :isProduction)
        (= "prod" mines) :isProduction
        :else (constantly true)))

(defn select-instances
  "The instances GET /service/instances returns for the query params:
  filtered by mines and q, sorted by name (binary order, as Mongo's sort).
  Whatever its status: a failed check never hides a mine (it is shown as
  Unreachable, with the reason in last_check)."
  [instances {:strs [q mines]}]
  (->> instances
       (filter (production-filter mines))
       (filter #(or (str/blank? q) (matches-q? q %)))
       (sort-by :name)))

(defn find-instance
  "By id, or namespace or name (case-insensitive), first in file order."
  [instances id-or-name]
  (let [l (str/lower-case id-or-name)]
    (first (filter #(or (= id-or-name (:id %))
                        (= l (str/lower-case (:namespace %)))
                        (= l (str/lower-case (:name %))))
                   instances))))

(defn normalize-url
  "url without scheme, leading www., trailing slashes; lower case."
  [url]
  (-> (str/lower-case (str/trim (str url)))
      (str/replace #"^https?://" "")
      (str/replace #"^www\." "")
      (str/replace #"/+$" "")))

(defn find-by-url
  "The instance for GET /service/namespace?url=. The Node app stripped the
  scheme and www. and returned the first instance whose URL contained the
  rest (case-insensitive), so www.flymine.org/flymine answered flymine.beta
  (https://beta.flymine.org/flymine came first in the collection). Here an
  exact match (after the same normalisation and without trailing slashes)
  wins; otherwise the first production instance, then the first instance,
  whose URL contains it."
  [instances url]
  (let [n (normalize-url url)
        contains (filter #(str/includes? (normalize-url (:url %)) n) instances)]
    (when-not (str/blank? n)
      (or (first (filter #(= n (normalize-url (:url %))) instances))
          (first (filter :isProduction contains))
          (first contains)))))

;; ---------------------------------------------------------------- handlers

(defn list-instances [params opts]
  (json-response 200 (envelope 200 :instances (mapv #(instance->json % opts) (select-instances (data/instances) params)))))

(defn get-instance [id opts]
  (if-let [inst (find-instance (data/instances) id)]
    (json-response 200 (envelope 200 :instance (instance->json inst opts)))
    ;; the Node app answered {"statusCode":404,"errorMsg":"Not Found",...}
    (json-response 404 (envelope 404 :errorMsg "Not Found" :message (str "Instance Not Found: " id)))))

(defn get-namespace [{:strs [url]}]
  (cond
    (str/blank? url)
    (json-response 400 (envelope 400 :message "Missing url parameter"))

    :else
    (if-let [inst (find-by-url (data/instances) url)]
      (json-response 200 (array-map :statusCode 200 :namespace (:namespace inst)))
      (json-response 404 (envelope 404 :message "Url Not Found")))))

(defn redirect-namespace
  "GET /{namespace}: 303 to the mine (exact, case-sensitive namespace, as the Node app)."
  [ns]
  (if-let [inst (first (filter #(= ns (:namespace %)) (data/instances)))]
    (resp/see-other (:url inst))
    (json-response 404 (envelope 404 :message "Namespace Not Found"))))

(def read-only-message
  (str "The InterMine Registry is read-only: the instances are maintained in resources/instances.edn at "
       repository-url ". To add a mine or change its details, open a pull request there."))

(defn read-only [_]
  (json-response 405 (envelope 405 :message read-only-message :friendlyMessage read-only-message)
                 {"Allow" "GET"}))

(defn get-logo
  "GET /instances/{namespace}/logo: the registry's copy of the mine's logo
  with its content type, cacheable for a day; 404 when there is none. (An SVG
  is served with a CSP that forbids scripts, as it is served from the
  registry's own origin.)"
  [namespace {:keys [cache-dir]}]
  (let [entry (get-in @data/state [:logos namespace])
        body (try (logos/read-logo cache-dir entry)
                  (catch java.io.IOException _ nil))]
    (if body
      {:status 200
       :headers (cond-> {"Content-Type" (:content-type entry)
                         "Cache-Control" "public, max-age=86400"
                         "X-Content-Type-Options" "nosniff"}
                  (:sha1 entry) (assoc "ETag" (str "\"" (:sha1 entry) "\""))
                  (= "image/svg+xml" (:content-type entry))
                  (assoc "Content-Security-Policy" "default-src 'none'; style-src 'unsafe-inline'; sandbox"))
       :body body}
      {:status 404
       :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body "No logo cached for this mine"})))

(defn health []
  (let [insts (data/instances)
        last-run (get-in @data/state [:refresh :last-run])]
    (json-response 200 (array-map :status "ok"
                                  :instances (count insts)
                                  :running (count (filter #(= "Running" (:status %)) insts))
                                  :unreachable (count (filter #(= "Unreachable" (:status %)) insts))
                                  :lastRefresh (:finished last-run)))))

(defn- bearer-token [req]
  (some->> (get-in req [:headers "authorization"]) (re-matches #"(?i)Bearer\s+(\S+)") second))

(defn- constant-time= [^String a ^String b]
  (java.security.MessageDigest/isEqual (.getBytes a "UTF-8") (.getBytes b "UTF-8")))

(defn synchronize
  "PUT /service/synchronize[/id]: refresh now. Needs Authorization: Bearer
  $REGISTRY_ADMIN_TOKEN; 405 when no token is configured."
  [req {:keys [admin-token] :as opts} id]
  (let [token (bearer-token req)]
    (cond
      (str/blank? admin-token)
      (json-response 405 (envelope 405 :message (str "Synchronisation on request is disabled: the server has no "
                                                     "REGISTRY_ADMIN_TOKEN. The registry refreshes every instance "
                                                     "on its own schedule (REGISTRY_REFRESH_MINUTES); see /status.")))

      (not (and token (constant-time= token admin-token)))
      (json-response 401 (envelope 401 :message "Unauthorized: send Authorization: Bearer <REGISTRY_ADMIN_TOKEN>")
                     {"WWW-Authenticate" "Bearer realm=\"InterMine Registry\""})

      (nil? id)
      (let [results (refresh/refresh! opts)]
        (json-response 200 (envelope 200
                                     :message (str (count results) " instances synchronised")
                                     :instances (mapv (fn [{:keys [instance changes errors]}]
                                                        (array-map :namespace (:namespace instance)
                                                                   :status (:status instance)
                                                                   :changes (mapv :field changes)
                                                                   :errors errors))
                                                      results))))

      :else
      (if-let [inst (find-instance (data/instances) id)]
        (let [[{:keys [instance changes errors]}] (refresh/refresh! (assoc opts :all? false) [inst])]
          ;; the Node app answered 201 with instance_id and a message
          (json-response 201 (envelope 201
                                       :instance_id (:id instance)
                                       :message (str "Instance " (:name instance) " Versions Updated")
                                       :friendlyMessage (str "Instance " (:name instance) " Versions Updated")
                                       :changes (mapv :field changes)
                                       :errors errors)))
        (json-response 404 (envelope 404 :message "Instance Not Found" :friendlyMessage "Instance Not Found"))))))

;; ---------------------------------------------------------------- route table (for /docs)

(def routes-doc
  [{:method "GET" :path "/service/instances"
    :summary "The instances, sorted by name; unreachable ones are listed too (status \"Unreachable\", the reason in last_check)."
    :params [["mines" "optional: absent = production instances only; dev = non-production only; prod = production only; all (or any other value) = both"]
             ["q" "optional: search; matches an organism exactly, a word of the description, or part of the name (case-insensitive)"]]
    :example "/service/instances"
    :returns "{\"instances\": [instance, ...], \"statusCode\": 200, \"executionTime\": \"...\"}"}
   {:method "GET" :path "/service/instances/{id}"
    :summary "One instance, by id, namespace or name (case-insensitive), whatever its status."
    :params [["id" "path: an id (\"35\"), namespace (\"flymine\") or name (\"FlyMine\")"]]
    :example "/service/instances/flymine"
    :returns "{\"instance\": instance, \"statusCode\": 200, \"executionTime\": \"...\"}; 404 {\"errorMsg\": \"Not Found\", \"message\": ..., \"statusCode\": 404}"}
   {:method "GET" :path "/service/namespace"
    :summary "The namespace of the instance at a URL (used by InterMine webapps to find themselves)."
    :params [["url" "required: the mine's URL, with or without scheme and www."]]
    :example "/service/namespace?url=www.flymine.org/flymine"
    :returns "{\"statusCode\": 200, \"namespace\": \"...\"}; 400 without url; 404 {\"message\": \"Url Not Found\"}"}
   {:method "GET" :path "/instances/{namespace}/logo"
    :summary "The registry's own copy of the mine's logo (fetched from the mine during the refresh; falls back to the mine's favicon), served with its image content type and Cache-Control: public, max-age=86400. Listed in the API as images.logo_cached."
    :params [["namespace" "path: exact namespace"]]
    :example nil
    :returns "the image (png, jpeg, gif, svg or ico); 404 text when the registry holds none yet"}
   {:method "GET" :path "/{namespace}"
    :summary "Redirects (303) to the mine's web application."
    :params [["namespace" "path: exact namespace"]]
    :example "/flymine"
    :returns "303 See Other, Location: the mine URL; 404 JSON when unknown"}
   {:method "GET" :path "/health"
    :summary "Liveness: instance counts and the time of the last refresh."
    :params []
    :example "/health"
    :returns "{\"status\": \"ok\", \"instances\": n, \"running\": n, \"unreachable\": n, \"lastRefresh\": \"...\"}"}
   {:method "PUT" :path "/service/synchronize"
    :summary "Refresh every instance now (admin)."
    :params [["Authorization" "header: Bearer $REGISTRY_ADMIN_TOKEN (405 when the server has no token, 401 when wrong)"]]
    :example nil
    :returns "{\"message\": \"n instances synchronised\", \"instances\": [...], \"statusCode\": 200}"}
   {:method "PUT" :path "/service/synchronize/{id}"
    :summary "Refresh one instance now (admin)."
    :params [["id" "path: id, namespace or name"]
             ["Authorization" "header: Bearer $REGISTRY_ADMIN_TOKEN"]]
    :example nil
    :returns "201 {\"instance_id\": \"...\", \"message\": \"Instance X Versions Updated\", ...}"}
   {:method "POST, PUT, DELETE" :path "/service/instances[/{id}]"
    :summary "Removed: the registry is read-only; change resources/instances.edn by pull request."
    :params []
    :example nil
    :returns "405 {\"message\": \"The InterMine Registry is read-only: ...\", \"statusCode\": 405}"}])
