(ns ivarref.az-utils
  (:require [babashka.process :refer [$ check]]
            [clojure.tools.logging :as log]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [aleph.http :as http]
            [byte-streams :as bs]
            [manifold.stream :as s])
  (:import (java.util UUID)))

(defn pretty-map [m]
  (walk/postwalk
    (fn [x]
      (if (map? x)
        (into (sorted-map) [x])
        x))
    m))

(defn resolve-container-name [{:keys [resource-group
                                      container-name]
                               :as   opts}]
  (if (not (str/ends-with? container-name "*"))
    container-name
    (as-> ^{:out :string} ($ az container list -g ~resource-group --query (str "[?starts_with(name, '" (subs container-name 0 (dec (count container-name))) "')].name")) v
          (check v)
          (:out v)
          (json/parse-string v keyword)
          (vec v)
          (do (assert (= 1 (count v)) "expected to find a single container")
              v)
          (first v)
          (do
            (log/info "resolved container name to" v)
            v))))

(def get-container-name (memoize resolve-container-name))

(defn resolve-subscription-id [_]
  (as-> ^{:out :string} ($ az account list) v
        (check v)
        (:out v)
        (json/parse-string v keyword)
        (vec v)
        (pretty-map v)
        (filter (comp true? :isDefault) v)
        (first v)
        (:id v)
        (try
          (UUID/fromString v)
          (log/info "resovled subscription id to" v)
          v
          (catch Exception e
            (assert false "could not resolve subscription id!")))))

(def get-subscription-id (memoize resolve-subscription-id))

(defn access-token [_]
  (as-> ^{:out :string} ($ az account get-access-token) v
        (check v)
        (:out v)
        (json/parse-string v keyword)
        (do
          (assert (= "Bearer" (:tokenType v)))
          (:accessToken v))))

(defn get-websocket [{:keys [resource-group
                             proxy-path]
                      :as   opts}]
  (log/info "creating remote proxy at" proxy-path "...")
  (let [subscriptionId (resolve-subscription-id opts)
        container-group-name (resolve-container-name opts)
        token (access-token opts)
        ; POST https://management.azure.com/subscriptions/{subscriptionId}/resourceGroups/{resourceGroupName}/providers/Microsoft.ContainerInstance/containerGroups/{containerGroupName}/containers/{containerName}/exec?api-version=2019-12-01
        url (str "https://management.azure.com/subscriptions/"
                 subscriptionId
                 "/resourceGroups/"
                 resource-group
                 "/providers/Microsoft.ContainerInstance/containerGroups/"
                 container-group-name
                 "/containers/"
                 container-group-name
                 "/exec?api-version=2019-12-01")
        resp @(http/post url {:headers {"authorization" (str "Bearer " token)
                                        "content-type"  "application/json"}
                              :body    (json/encode
                                         {:command      proxy-path
                                          :terminalSize {:rows 80
                                                         :cols 24}})})]
    (if (not= 200 (:status resp))
      (do
        (log/error "could not create remote proxy!")
        (log/error "got http status code" (:status resp))
        (log/error "http body:\n" (-> resp :body bs/to-string))
        nil)
      (let [{:keys [webSocketUri password] :as body}
            (-> resp
                :body
                bs/to-string
                (json/decode keyword))
            _ (log/info "connecting to websocket URL" webSocketUri "...")
            remote-websocket @(http/websocket-client webSocketUri)]
        (log/info "entering password ...")
        @(s/put! remote-websocket password)
        remote-websocket))))