(ns playground.graphql
  (:require [clojure.edn :as edn]
            [clojure.string :as s]
            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.lacinia.pedestal2 :as lacinia.pedestal2]
            [com.walmartlabs.lacinia.util :as util]
            [integrant.core :as ig]
            [io.pedestal.http :as http]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.log :as log]
            [playground.resolvers.greetings :as greetings])
  (:import [java.util UUID]))

(def resolvers-map
  {:greeting greetings/resolve-greetings})

(defmethod ig/init-key ::schema
  [_ {:keys [resource]}]
  (-> resource
      slurp
      edn/read-string
      (util/attach-resolvers resolvers-map)
      schema/compile))

(def log-request
  "An Interceptor that logs http requests and their responses with the response time."
  {:name ::log-request
   :enter (fn [context]
            (let [{:keys [uri request-method]} (:request context)
                  request-id (UUID/randomUUID)
                  start-time (System/currentTimeMillis)]
              (log/info :msg "received request"
                        :request-id request-id
                        :method (-> request-method name s/upper-case)
                        :uri uri)
              (-> context
                  (assoc-in [:request :request-id] request-id)
                  (assoc-in [:request :start-time] start-time))))
   :leave (fn [context]
            (let [{:keys [request-id start-time]} (:request context)
                  finish-time (System/currentTimeMillis)
                  total (- finish-time start-time)]
              (log/info :msg "completed request"
                        :request-id request-id
                        :status (-> context :response :status)
                        :response-time total)
              context))})

(defmethod ig/init-key ::service
  [_ {:keys [schema options]}]
  (let [{:keys [app-context env]} options
        interceptors (lacinia.pedestal2/default-interceptors schema app-context)
        routes (fn [{:keys [api-path ide-path asset-path] :as graphiql-options}]
                 (into #{[api-path :post interceptors
                          :route-name ::graphql-api]
                         [ide-path :get (lacinia.pedestal2/graphiql-ide-handler graphiql-options)
                          :route-name ::graphiql-ide]}
                       (lacinia.pedestal2/graphiql-asset-routes asset-path)))]
    (lacinia.pedestal2/enable-graphiql
     {:env env
      ::http/routes          (routes options)
      ::http/request-logger  (interceptor log-request)
      ::http/allowed-origins (constantly true)})))
