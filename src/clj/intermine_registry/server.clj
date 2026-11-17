(ns intermine-registry.server
  "Routes and middleware."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [compojure.core :refer [ANY DELETE GET POST PUT context routes]]
            [compojure.route :as route]
            [intermine-registry.api :as api]
            [intermine-registry.pages :as pages]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.head :refer [wrap-head]]
            [ring.middleware.not-modified :refer [wrap-not-modified]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.http-response :as resp]))

(defn- html [body] (-> (resp/ok body) (resp/content-type "text/html; charset=utf-8")))

(defn app-routes [opts]
  (routes
   (context "/service" []
     (GET "/instances" {params :query-params} (api/list-instances params opts))
     (GET "/instances/" {params :query-params} (api/list-instances params opts))
     (GET "/instances/:id" [id] (api/get-instance id opts))
     (POST "/instances" req (api/read-only req))
     (POST "/instances/" req (api/read-only req))
     (ANY "/instances/:id" req (api/read-only req))
     (GET "/namespace" {params :query-params} (api/get-namespace params))
     (PUT "/synchronize" req (api/synchronize req opts nil))
     (PUT "/synchronize/" req (api/synchronize req opts nil))
     (PUT "/synchronize/:id" [id :as req] (api/synchronize req opts id))
     (GET "/" [] (resp/found "/docs")))
   (GET "/service" [] (resp/found "/docs"))
   (GET "/health" [] (api/health))
   (GET "/" {params :query-params} (html (pages/home params opts)))
   (GET "/instances/:ns/logo" [ns] (api/get-logo ns opts))
   (GET "/instances/:ns" [ns] (if-let [page (pages/instance-page ns opts)]
                                (html page)
                                (-> (resp/not-found (pages/not-found (str "/instances/" ns) opts))
                                    (resp/content-type "text/html; charset=utf-8"))))
   ;; Galaxy integration pages of the Node app (Galaxy's InterMine data source)
   (GET "/im-to-galaxy" {params :query-params}
     (html (pages/home params opts {:mode :to-galaxy :url (get params "GALAXY_URL")})))
   (GET "/galaxy-to-im" {params :query-params}
     (html (pages/home params opts {:mode :from-galaxy :url (get params "URL")})))
   (GET "/docs" [] (html (pages/docs opts)))
   (GET "/status" [] (html (pages/status-page opts)))
   (GET "/api-docs" [] (resp/moved-permanently "/docs"))
   (GET "/api-docs/" [] (resp/moved-permanently "/docs"))
   (route/resources "/" {:root "public"})
   (GET "/:ns" [ns] (api/redirect-namespace ns))
   (route/not-found {:status 404
                     :headers {"Content-Type" "application/json; charset=utf-8"}
                     :body "{\"statusCode\":404,\"message\":\"Not Found\"}"})))

(defn wrap-cors
  "Cross-origin GETs of the API (BlueGenes reads it from the browser), with
  the headers the Node app sent."
  [handler]
  (fn [req]
    (let [response (handler req)]
      (when response
        (cond-> response
          (str/starts-with? (:uri req) "/service")
          (update :headers merge {"Access-Control-Allow-Origin" "*"
                                  "Access-Control-Allow-Headers" "Origin, X-Requested-With, Content-Type, Accept, Authorization"}))))))

(defn wrap-options
  "CORS preflight for /service/*."
  [handler]
  (fn [req]
    (if (and (= :options (:request-method req)) (str/starts-with? (:uri req) "/service"))
      {:status 204 :headers {"Allow" "GET, PUT, OPTIONS"
                             "Access-Control-Allow-Methods" "GET, PUT, OPTIONS"} :body ""}
      (handler req))))

(defn wrap-errors [handler]
  (fn [req]
    (try (handler req)
         (catch Exception e
           (log/error e "Request failed" (:uri req))
           (api/json-response 500 {:statusCode 500 :message "Internal Server Error"})))))

(defn handler [opts]
  (-> (app-routes opts)
      wrap-params
      wrap-options
      wrap-cors
      wrap-content-type
      wrap-not-modified
      wrap-head
      wrap-errors))
