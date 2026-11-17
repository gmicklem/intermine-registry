(ns intermine-registry.data
  "The registry's data: the instances file (resources/instances.edn or
  REGISTRY_INSTANCES_FILE), its clojure.spec validation, the in-memory state
  and the on-disk cache of the refreshed values."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.io File]
           [java.nio.file Files StandardCopyOption]
           [java.time Instant ZoneOffset]
           [java.time.format DateTimeFormatter]
           [java.util Locale]))

;; ---------------------------------------------------------------- time

(def ^:private iso-format
  (-> (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
      (.withZone ZoneOffset/UTC)))

(def ^:private execution-time-format
  ;; what the Node app's new Date().toLocaleString() gave on Heroku (UTC, en-US):
  ;; "11/17/2026, 2:10:58 AM"
  (-> (DateTimeFormatter/ofPattern "M/d/yyyy, h:mm:ss a" Locale/US)
      (.withZone ZoneOffset/UTC)))

(defn iso-now
  "The current time as the ISO string Mongo/JSON gave: 2026-11-17T00:00:32.310Z"
  ([] (iso-now (Instant/now)))
  ([^Instant t] (.format ^DateTimeFormatter iso-format t)))

(defn execution-time [] (.format ^DateTimeFormatter execution-time-format (Instant/now)))

;; ---------------------------------------------------------------- spec

(def reserved-namespaces
  "Namespaces that would clash with the registry's own top-level routes."
  #{"service" "instances" "docs" "status" "health" "css" "api-docs" "favicon.ico" "robots.txt" "im-to-galaxy" "galaxy-to-im"})

(defn- http-url? [v]
  (and (string? v) (boolean (re-matches #"https?://[^\s/$.?#][^\s]*" v))))

(defn- optional-url? [v] (or (= "" v) (http-url? v)))

(defn iso-timestamp? [v]
  (and (string? v) (boolean (re-matches #"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d{1,3})?Z" v))))

(defn api-version? [v] (and (string? v) (boolean (re-matches #"\d{1,4}" v))))

(defn version-string?
  "A short version string such as \"5.1.0\" or \"v53 February 2022\" (not an
  HTML page served instead of the version, not an unsubstituted @TOKEN@)."
  [v]
  (and (string? v)
       (<= (count v) 64)
       (not (re-find #"[\r\n<>{}\[\]]" v))
       (not (re-find #"(?i)doctype|html" v))
       (not (re-matches #"@[A-Z_]+@" v))))

(s/def ::string string?)
(s/def ::id (s/and string? #(re-matches #"[A-Za-z0-9._-]+" %)))
(s/def ::name (s/and string? (complement str/blank?)))
(s/def ::namespace (s/and string?
                          #(re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" %)
                          #(not (reserved-namespaces (str/lower-case %)))))
(s/def ::url http-url?)
(s/def ::description string?)
(s/def ::organisms (s/coll-of string? :kind vector?))
(s/def ::neighbours (s/coll-of string? :kind vector?))
(s/def ::latitude string?)
(s/def ::longitude string?)
(s/def ::location (s/keys :req-un [::latitude ::longitude]))
(s/def ::maintainerOrgName string?)
(s/def ::maintainerUrl optional-url?)
(s/def ::maintainerEmail string?)
(s/def ::maintainerGithubUrl optional-url?)
(s/def ::twitter string?)
(s/def ::isProduction boolean?)
(s/def ::created_at (s/nilable iso-timestamp?))
;; refreshed fields (initial cache)
(s/def ::api_version (s/or :empty #{""} :version api-version?))
(s/def ::intermine_version (s/or :empty #{""} :version version-string?))
(s/def ::release_version (s/or :empty #{""} :version version-string?))
(def statuses
  "The values of `status`. \"Not Running\" is what version 2.0 before im-bdy.15
  and the Node registry wrote; `normalize-instance` turns it into \"Unreachable\"."
  #{"Running" "Unreachable" "Not checked yet" "Not Running"})
(s/def ::status statuses)
(s/def ::outcome #{"ok" "failed"})
(s/def ::reason (s/nilable (s/and string? #(<= (count %) 200))))
(s/def ::time iso-timestamp?)
(s/def ::since (s/nilable iso-timestamp?))
(s/def ::last_check (s/nilable (s/keys :req-un [::time ::outcome ::reason] :opt-un [::since])))
(s/def ::colors (s/map-of keyword? (s/or :string string? :map (s/map-of keyword? string?))))
(s/def ::images (s/map-of keyword? string?))
(s/def ::last_time_updated (s/nilable iso-timestamp?))

(s/def ::instance
  (s/keys :req-un [::id ::name ::namespace ::url ::description ::organisms ::neighbours
                   ::location ::maintainerOrgName ::maintainerUrl ::maintainerEmail
                   ::maintainerGithubUrl ::twitter ::isProduction ::created_at]
          :opt-un [::api_version ::intermine_version ::release_version ::status
                   ::colors ::images ::last_time_updated ::last_check]))

(defn- duplicates [xs]
  (->> (frequencies xs) (filter #(> (val %) 1)) (map key) sort))

(s/def ::instances
  (s/and (s/coll-of ::instance :kind vector? :min-count 1)
         #(empty? (duplicates (map (comp str/lower-case :namespace) %)))
         #(empty? (duplicates (map :id %)))))

(def fixed-keys
  "The hand-maintained keys, in file order."
  [:id :name :namespace :url :description :organisms :neighbours :location
   :maintainerOrgName :maintainerUrl :maintainerEmail :maintainerGithubUrl
   :twitter :isProduction :created_at])

(def refreshed-keys
  "The keys the refresh sets."
  [:api_version :intermine_version :release_version :status :colors :images :last_time_updated
   :last_check])

(defn- explain-problems
  "A readable list of what is wrong with the instances vector."
  [instances]
  (let [names (fn [i] (str "instance " (pr-str (or (:namespace (nth instances i nil)) (str "#" i)))))
        problems (for [{:keys [in pred val]} (::s/problems (s/explain-data ::instances instances))]
                   (let [[i & path] in]
                     (if (and (int? i) (seq path))
                       (str (names i) ", key " (pr-str (first path)) ": value " (pr-str val)
                            " fails " (pr-str pred))
                       (str (if (int? i) (str (names i) ": ") "")
                            (if-let [[_ k] (re-find #"contains\? % (:\S+)\)" (pr-str pred))]
                              (str "missing key " k)
                              (str "fails " (pr-str pred)))))))
        dup-ns (duplicates (map (comp str/lower-case str :namespace) instances))
        dup-id (duplicates (map (comp str :id) instances))]
    (concat problems
            (when (seq dup-ns) [(str "duplicate namespaces (case-insensitive): " (str/join ", " dup-ns))])
            (when (seq dup-id) [(str "duplicate ids: " (str/join ", " dup-id))]))))

(defn validate!
  "Returns instances if they conform to ::instances, else throws an ex-info
  whose message lists every problem (source names the file)."
  [instances source]
  (when-not (s/valid? ::instances instances)
    (throw (ex-info (str "Invalid instances file " source ":\n  - "
                         (str/join "\n  - " (distinct (explain-problems instances))))
                    {:source source})))
  instances)

(defn read-instances
  "Reads and validates the instances file: REGISTRY_INSTANCES_FILE (a path)
  if given, else resources/instances.edn from the classpath."
  [path]
  (let [source (if path (io/file path) (io/resource "instances.edn"))
        label (if path (str path) "instances.edn (classpath)")]
    (when-not (and source (or (not path) (.exists ^File source)))
      (throw (ex-info (str "Instances file not found: " label) {:source label})))
    (let [data (try (edn/read-string (slurp source))
                    (catch Exception e
                      (throw (ex-info (str "Instances file " label " is not valid EDN: " (.getMessage e))
                                      {:source label} e))))]
      (validate! data label))))

;; ---------------------------------------------------------------- state

(defonce ^{:doc "{:instances [instance ...]   ; file order, refreshed fields merged in
            :refresh {:last-run {:started iso :finished iso :count n}
                      :results {namespace {:checked iso :status s :changes [...] :errors [...]}}}}"}
  state
  (atom {:instances [] :refresh {}}))

(defn instances [] (:instances @state))

;; ---------------------------------------------------------------- cache

(defn cache-file ^File [dir] (io/file dir "registry-cache.edn"))

(defn valid-refreshed-value?
  "Is v an acceptable value for the refreshed key k (cache contents are not
  trusted blindly: a bad value is ignored)?"
  [k v]
  (case k
    :api_version (s/valid? ::api_version v)
    :intermine_version (s/valid? ::intermine_version v)
    :release_version (s/valid? ::release_version v)
    :status (s/valid? ::status v)
    :colors (s/valid? ::colors v)
    :images (s/valid? ::images v)
    :last_time_updated (s/valid? ::last_time_updated v)
    :last_check (s/valid? ::last_check v)
    false))

(defn read-cache
  "The cache map {:instances {namespace {refreshed-key value}} :refresh {...}},
  or nil when there is none or it cannot be read."
  [dir]
  (let [f (cache-file dir)]
    (when (.exists f)
      (try (edn/read-string (slurp f))
           (catch Exception e
             (log/warn "Ignoring unreadable cache" (str f) (.getMessage e))
             nil)))))

(defn merge-cache
  "Instances with the cached refreshed values (valid ones only) merged in."
  [instances cache]
  (let [cached (:instances cache)]
    (mapv (fn [inst]
            (let [values (get cached (:namespace inst))]
              (reduce-kv (fn [m k v] (if (valid-refreshed-value? k v) (assoc m k v) m))
                         inst
                         (select-keys values refreshed-keys))))
          instances)))

(defn write-cache!
  "Writes the refreshed values and the last refresh results to dir
  (atomically: a temporary file then a rename). Logs and returns nil on
  failure: the cache is a convenience, the registry works without it."
  [dir {:keys [instances refresh]}]
  (try
    (let [f (cache-file dir)
          tmp (io/file dir "registry-cache.edn.tmp")]
      (.mkdirs (io/file dir))
      (spit tmp (pr-str {:saved-at (iso-now)
                         :instances (into {} (map (juxt :namespace #(select-keys % refreshed-keys))) instances)
                         :refresh refresh}))
      (Files/move (.toPath tmp) (.toPath f)
                  (into-array [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))
      f)
    (catch Exception e
      (log/warn "Could not write the cache in" (str dir) (.getMessage e))
      nil)))

;; ---------------------------------------------------------------- check outcome

(defn normalize-instance
  "Gives an instance the `status` and `last_check` of version 2.0 (pure):
  - last_check present: unchanged;
  - status \"Not Running\" (the Node registry, or 2.0 before im-bdy.15): \"Unreachable\",
    a failed last_check at last_time_updated, reason \"not running (recorded
    before reasons were kept)\";
  - last_time_updated present (the seeded capture, a pre-im-bdy.15 cache):
    \"Running\", an ok last_check at last_time_updated;
  - otherwise \"Not checked yet\" and last_check nil."
  [{:keys [status last_check last_time_updated] :as inst}]
  (cond
    (contains? inst :last_check) (cond-> inst (nil? status) (assoc :status "Not checked yet"))
    (nil? last_time_updated) (assoc inst :status "Not checked yet" :last_check nil)
    (= "Not Running" status) (assoc inst
                                    :status "Unreachable"
                                    :last_check {:time last_time_updated :outcome "failed"
                                                 :reason "not running (recorded before reasons were kept)"
                                                 :since last_time_updated})
    :else (assoc inst :status "Running"
                 :last_check {:time last_time_updated :outcome "ok" :reason nil :since nil})))

(defn load!
  "Reads the instances file and the cache into state. Throws if the file is invalid."
  [{:keys [instances-file cache-dir]}]
  (let [instances (read-instances instances-file)
        cache (when cache-dir (read-cache cache-dir))]
    (reset! state {:instances (mapv normalize-instance (merge-cache instances cache))
                   :refresh (or (:refresh cache) {})})
    (log/info "Loaded" (count instances) "instances" (if cache "and the cache" "(no cache yet)"))
    @state))
