(ns intermine-registry.refresh
  "Refreshes the volatile fields of each instance from the mine itself, as
  the Node app's scheduled/automaticUpdate.js and routes/synchronize.js did:

    GET {url}/service/version/intermine -> intermine_version; a version
                                           answer means the check succeeded
                                           (see apply-results for status and
                                           last_check)
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
    200 with something that is not a version has a failed check;
  - a failed check never hides a mine: the instance keeps its last known
    versions and branding, gets a `last_check` {:time :outcome :reason} and,
    from the second consecutive failure, status \"Unreachable\" (see
    classify-failure for the reasons);
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
                              (let [c (or (.getCause ^Throwable e) e)]
                                {:error (str (.getSimpleName (class c))
                                             (when-let [m (.getMessage ^Throwable c)] (str ": " m)))
                                 :error-class (.getSimpleName (class c))
                                 :error-message (.getMessage ^Throwable c)}))))))
    (catch Exception e
      (CompletableFuture/completedFuture {:error (str "bad URL " url ": " (.getMessage e))
                                          :error-class "BadUrl"}))))

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

(defn html-body?
  "Does the body look like a web page (a bot-protection challenge, an error
  page) rather than a version number or JSON?"
  [body]
  (let [b (str/triml (str body))]
    (boolean (or (str/starts-with? b "<")
                 (re-find #"(?i)<!doctype|<html[\s>]|<head[\s>]|<body[\s>]" b)))))

(defn bot-protection-body?
  "Is this web page a bot-protection challenge: it mentions Anubis, has a
  `<meta name=\"generator\"` tag (Anubis sets one), or a <title> that speaks
  of a challenge, a verification or being a bot."
  [body]
  (let [b (str body)
        title (second (re-find #"(?is)<title[^>]*>(.*?)</title>" b))]
    (boolean (or (re-find #"(?i)anubis" b)
                 (re-find #"(?i)<meta\s+name\s*=\s*[\"']generator[\"']" b)
                 (and title (re-find #"(?i)challenge|verif|not a bot|just a moment|checking your browser" title))))))

(defn classify-failure
  "A short reason for a response that is not a version answer:
    exception                  \"timeout\" (HttpTimeoutException, a timed-out connect),
                               \"connection refused\", \"unknown host\", \"TLS error\",
                               \"bad URL\", \"connection error: <Class>\", else the class name
    HTML with anubis, a <meta name=\"generator\"> or a challenge/verification
    title (whatever the status)  \"bot-protection page\"
    non-2xx                    \"http NNN\"
    other HTML                 \"html instead of json\"
    too large                  \"answer too large\"
    otherwise                  \"invalid version string\""
  [{:keys [status error error-class error-message body]}]
  (cond
    (or error error-class)
    (let [msg (str/lower-case (str error-message))]
      (case error-class
        ("HttpTimeoutException" "HttpConnectTimeoutException" "TimeoutException") "timeout"
        "ConnectException" (if (str/includes? msg "timed out") "timeout" "connection refused")
        "UnknownHostException" "unknown host"
        ("SSLHandshakeException" "SSLException" "SSLPeerUnverifiedException" "CertificateException") "TLS error"
        "BadUrl" "bad URL"
        ("IOException" "ClosedChannelException" "EOFException" "SocketException")
        (str "connection error: " error-class)
        (or error-class "unknown error")))
    (and (html-body? body) (bot-protection-body? body)) "bot-protection page"
    (not (and status (<= 200 status 299))) (str "http " status)
    (html-body? body) "html instead of json"
    (> (count body) max-body-chars) "answer too large"
    :else "invalid version string"))

(defn- version-result
  "{:value v} if the response is a 200 whose sanitized body passes valid?,
  else {:error message :reason short-reason}."
  [resp valid?]
  (let [{:keys [status body]} resp
        v (when (and (= 200 status) (<= (count body) max-body-chars)) (sanitize body))]
    (if (and v (not (str/blank? v)) (valid? v) (not (html-body? body)))
      {:value v}
      {:error (describe-failure resp) :reason (classify-failure resp)})))

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

(def unreachable-after
  "Consecutive failed checks before a Running mine is marked Unreachable."
  2)

(defn- check-state
  "{:status :last_check} after this check (pure). A success: Running, outcome
  ok. A failure is recorded in last_check at once; the status becomes
  \"Unreachable\" at the second consecutive failure, or at the first when the
  mine was not Running before (never checked: nothing to keep); the first
  failure of a Running mine keeps \"Running\" (two-strikes rule). `since` is
  the time of the first failure of the current run of failures."
  [instance reason now]
  (let [prev (:last_check instance)
        prev-failed? (= "failed" (:outcome prev))]
    (if-not reason
      {:status "Running" :last_check {:time now :outcome "ok" :reason nil :since nil}}
      {:status (if (and (= "Running" (:status instance)) (not prev-failed?)) "Running" "Unreachable")
       :last_check {:time now :outcome "failed" :reason reason
                    :since (if prev-failed? (or (:since prev) (:time prev) now) now)}})))

(defn apply-results
  "The instance updated with the fetch results (pure). Returns
  {:instance updated :changes [{:field k :from a :to b}] :errors [..]}.
  When /service/version/intermine gave a version the check succeeded and all
  values are applied; otherwise only status, last_check and last_time_updated
  change (the last known versions and branding are kept)."
  [instance {:keys [intermine release api branding]} now]
  (let [ok? (some? (:value intermine))
        errors (cond-> []
                 (:error intermine) (conj (str "/service/version/intermine: " (:error intermine)))
                 (:error release) (conj (str "/service/version/release: " (:error release)))
                 (:error api) (conj (str "/service/version: " (:error api)))
                 (:error branding) (conj (str "/service/branding: " (:error branding)))
                 (:warning branding) (conj (str "/service/branding: " (:warning branding))))
        updated (-> (merge instance (check-state instance (when-not ok? (or (:reason intermine) "invalid version string")) now))
                    (assoc :last_time_updated now))
        updated (cond-> updated
                  ok? (assoc :intermine_version (:value intermine))
                  (and ok? (:value release)) (assoc :release_version (:value release))
                  (and ok? (:value api)) (assoc :api_version (:value api))
                  (and ok? (not (:error branding))) (assoc :colors (:colors branding) :images (:images branding)))
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
                                                          (assoc :status (get-in r [:instance :status])
                                                                 :last_check (get-in r [:instance :last_check])))]))
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
