(ns intermine-registry.refresh
  "Refreshes the volatile fields of each instance from the mine itself, as
  the Node app's scheduled/automaticUpdate.js and routes/synchronize.js did:

    GET {url}/service/version/intermine -> intermine_version; HTTP 200 means
                                           status \"Running\", anything else
                                           \"Not Running\"
    GET {url}/service/version/release   -> release_version
    GET {url}/service/version           -> api_version
    GET {url}/service/branding          -> colors, images (properties.colors,
                                           properties.images); unparsable
                                           JSON gives {} and {}

  then release_version and intermine_version are blanked when they equal
  api_version (a mine answering every path with the same page), and
  last_time_updated is set. Organisms are not refreshed (they never were:
  they are a hand-maintained field of the instances file).

  Differences from the Node code, all deliberate:
  - every value is validated (api_version an integer string, versions short
    strings, not HTML pages); a rejected value keeps the previous one and is
    reported on /status, and a mine whose /service/version/intermine answers
    200 with something that is not a version is \"Not Running\";
  - 10 s timeouts (the Node code used 2-5 s), a User-Agent header, at most
    `concurrency` mines at a time;
  - a failed branding call keeps the previous colors and images."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [intermine-registry.data :as data])
  (:import [java.net ProxySelector URI]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]
           [java.util.concurrent CompletableFuture Executors ScheduledExecutorService TimeUnit]))

(def user-agent "InterMine-Registry/2.0 (+https://registry.intermine.org)")

(def ^:dynamic *timeout-ms* 10000)

(def ^:private max-body-chars
  "Version and branding answers are tiny; anything larger is not one."
  65536)

(defonce ^:private client
  (delay (-> (HttpClient/newBuilder)
             (.followRedirects HttpClient$Redirect/NORMAL)
             (.connectTimeout (Duration/ofMillis *timeout-ms*))
             ;; honours -Dhttps.proxyHost etc. when set; direct otherwise
             (.proxy (ProxySelector/getDefault))
             (.build))))

(defn- service-url [base path]
  (str (str/replace base #"/+$" "") path))

(defn http-get-async
  "GETs url; a CompletableFuture of {:status n :body s} or {:error message}."
  [url]
  (try
    (let [req (-> (HttpRequest/newBuilder (URI/create url))
                  (.timeout (Duration/ofMillis *timeout-ms*))
                  (.header "User-Agent" user-agent)
                  (.header "Accept" "text/plain, application/json;q=0.9, */*;q=0.1")
                  (.GET)
                  (.build))]
      (-> (.sendAsync ^HttpClient @client req (HttpResponse$BodyHandlers/ofString))
          (.thenApply (reify java.util.function.Function
                        (apply [_ resp]
                          {:status (.statusCode ^java.net.http.HttpResponse resp)
                           :body (str (.body ^java.net.http.HttpResponse resp))})))
          (.exceptionally (reify java.util.function.Function
                            (apply [_ e]
                              {:error (let [c (or (.getCause ^Throwable e) e)]
                                        (str (.getSimpleName (class c))
                                             (when-let [m (.getMessage ^Throwable c)] (str ": " m))))})))))
    (catch Exception e
      (CompletableFuture/completedFuture {:error (str "bad URL " url ": " (.getMessage e))}))))

(defn sanitize
  "The Node app's clean-up of a version answer: strip `'\"<>{}[]\\/ and trim."
  [s]
  (str/trim (str/replace (str s) #"[`'\"<>{}\[\]\\/]" "")))

(defn- describe-failure [{:keys [status error body]}]
  (cond error error
        (not= 200 status) (str "HTTP " status)
        (> (count body) max-body-chars) (str "answer too large (" (count body) " characters)")
        :else (let [s (str/trim body)]
                (str "not a version: " (pr-str (subs s 0 (min 60 (count s))))))))

(defn- version-result
  "{:value v} if the response is a 200 whose sanitized body passes valid?,
  else {:error message}."
  [resp valid?]
  (let [{:keys [status body]} resp
        v (when (and (= 200 status) (<= (count body) max-body-chars)) (sanitize body))]
    (if (and v (valid? v))
      {:value v}
      {:error (describe-failure resp)})))

(defn- string-map?
  "{keyword string} or {keyword {keyword string}}."
  [m]
  (and (map? m)
       (every? (fn [[k v]] (and (keyword? k)
                                (or (string? v)
                                    (and (map? v) (every? (fn [[k2 v2]] (and (keyword? k2) (string? v2))) v)))))
               m)))

(defn branding-result
  "{:colors .. :images ..} from a /service/branding response,
  {:colors {} :images {}} for a 200 that is not the expected JSON (as the
  Node code did), {:error ..} for a failed call."
  [{:keys [status body] :as resp}]
  (if (and (= 200 status) (<= (count body) max-body-chars))
    (let [parsed (try (json/parse-string body true) (catch Exception _ nil))
          colors (get-in parsed [:properties :colors])
          images (get-in parsed [:properties :images])
          images (when (map? images) (into {} (filter (comp string? val)) images))]
      (if (and (string-map? colors) (string-map? images))
        {:colors colors :images images}
        {:colors {} :images {} :warning "branding is not the expected JSON; colors and images cleared"}))
    {:error (describe-failure resp)}))

(defn fetch
  "Calls the four endpoints of the mine at base-url in parallel; returns
  {:intermine {...} :release {...} :api {...} :branding {...}}."
  [base-url]
  (let [paths {:intermine "/service/version/intermine"
               :release "/service/version/release"
               :api "/service/version"
               :branding "/service/branding"}
        futures (into {} (map (fn [[k p]] [k (http-get-async (service-url base-url p))])) paths)
        responses (into {} (map (fn [[k ^CompletableFuture f]] [k (.join f)])) futures)]
    {:intermine (version-result (:intermine responses) data/version-string?)
     :release (version-result (:release responses) data/version-string?)
     :api (version-result (:api responses) data/api-version?)
     :branding (branding-result (:branding responses))}))

(defn apply-results
  "The instance updated with the fetch results (pure). Returns
  {:instance updated :changes [{:field k :from a :to b}] :errors [..]}."
  [instance {:keys [intermine release api branding]} now]
  (let [errors (cond-> []
                 (:error intermine) (conj (str "/service/version/intermine: " (:error intermine)))
                 (:error release) (conj (str "/service/version/release: " (:error release)))
                 (:error api) (conj (str "/service/version: " (:error api)))
                 (:error branding) (conj (str "/service/branding: " (:error branding)))
                 (:warning branding) (conj (str "/service/branding: " (:warning branding))))
        updated (cond-> (assoc instance
                               :status (if (:value intermine) "Running" "Not Running")
                               :last_time_updated now)
                  (:value intermine) (assoc :intermine_version (:value intermine))
                  (:value release) (assoc :release_version (:value release))
                  (:value api) (assoc :api_version (:value api))
                  (not (:error branding)) (assoc :colors (:colors branding) :images (:images branding)))
        ;; as the Node code: a version equal to the API version is not one
        updated (cond-> updated
                  (and (seq (:api_version updated)) (= (:api_version updated) (:release_version updated)))
                  (assoc :release_version "")
                  (and (seq (:api_version updated)) (= (:api_version updated) (:intermine_version updated)))
                  (assoc :intermine_version ""))
        changes (vec (for [k [:status :api_version :intermine_version :release_version :colors :images]
                           :when (not= (get instance k) (get updated k))]
                       {:field (name k) :from (get instance k) :to (get updated k)}))]
    {:instance updated :changes changes :errors errors}))

(defn refresh-instance
  "Fetches and applies; returns the apply-results map plus :checked and :ms."
  [instance]
  (let [t0 (System/nanoTime)
        fetched (fetch (:url instance))
        now (data/iso-now)
        result (apply-results instance fetched now)]
    (assoc result
           :checked now
           :ms (quot (- (System/nanoTime) t0) 1000000))))

(defn- store-results!
  "Swaps the refreshed instances into data/state (matched by namespace, so
  a concurrent reload is not undone) and records the per-mine results."
  [results started]
  (let [by-ns (into {} (map (juxt (comp :namespace :instance) identity)) results)]
    (swap! data/state
           (fn [st]
             (-> st
                 (update :instances (fn [insts] (mapv #(get-in by-ns [(:namespace %) :instance] %) insts)))
                 (update-in [:refresh :results]
                            merge (into {} (map (fn [[ns r]]
                                                  [ns (-> (select-keys r [:checked :ms :changes :errors])
                                                          (assoc :status (get-in r [:instance :status])))]))
                                        by-ns))
                 (cond-> started (assoc-in [:refresh :last-run] {:started started
                                                                 :finished (data/iso-now)
                                                                 :count (count results)})))))))

(defn refresh!
  "Refreshes the given instances (default: all), at most concurrency at a
  time, stores the results and writes the cache (when cache-dir is set).
  Returns the results."
  ([opts] (refresh! opts (data/instances)))
  ([{:keys [concurrency cache-dir all?] :or {concurrency 4}} instances]
   (let [started (data/iso-now)
         pool (Executors/newFixedThreadPool (int (max 1 concurrency)))
         results (try
                   (->> instances
                        (mapv (fn [inst] (.submit pool ^Callable (fn []
                                                                   (try (refresh-instance inst)
                                                                        (catch Throwable e
                                                                          (log/error e "Refresh failed for" (:namespace inst))
                                                                          {:instance inst :changes []
                                                                           :errors [(str "refresh failed: " (.getMessage e))]
                                                                           :checked (data/iso-now)}))))))
                        (mapv #(.get ^java.util.concurrent.Future %)))
                   (finally (.shutdown pool)))]
     (store-results! results (when (not= false all?) started))
     (doseq [{:keys [instance changes errors]} results]
       (log/info "Refreshed" (:namespace instance) (:status instance)
                 (if (seq changes) (str "changed: " (str/join ", " (map :field changes))) "unchanged")
                 (if (seq errors) (str "errors: " (str/join "; " errors)) "")))
     (when cache-dir (data/write-cache! cache-dir @data/state))
     results)))

;; ---------------------------------------------------------------- scheduler

(defonce ^:private scheduler (atom nil))

(defn stop-scheduler! []
  (when-let [^ScheduledExecutorService s @scheduler]
    (.shutdownNow s)
    (reset! scheduler nil)))

(defn start-scheduler!
  "Refreshes every instance every `minutes` (0 or less: never), the first
  time after initial-delay-s seconds."
  [{:keys [minutes initial-delay-s] :or {initial-delay-s 10} :as opts}]
  (stop-scheduler!)
  (if (and minutes (pos? minutes))
    (let [s (Executors/newSingleThreadScheduledExecutor
             (reify java.util.concurrent.ThreadFactory
               (newThread [_ r] (doto (Thread. ^Runnable r "registry-refresh") (.setDaemon true)))))]
      (.scheduleWithFixedDelay s
                               ^Runnable (fn [] (try (refresh! opts)
                                                     (catch Throwable e (log/error e "Scheduled refresh failed"))))
                               (long initial-delay-s) (long (* 60 minutes)) TimeUnit/SECONDS)
      (reset! scheduler s)
      (log/info "Refresh scheduled every" minutes "minutes")
      s)
    (log/info "Scheduled refresh disabled (REGISTRY_REFRESH_MINUTES=0)")))
