(ns ivarref.aci-tcp-proxy
  (:require [aleph.tcp :as tcp]
            [babashka.process :refer [$ check]]
            [clojure.walk :as walk]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.util UUID)))

(defn pretty-map [m]
  (walk/postwalk
    (fn [x]
      (if (map? x)
        (into (sorted-map) [x])
        x))
    m))

(defn not-empty-string [s]
  (and (string? s)
       (not-empty s)))

(defn resolve-container-name [{:keys [resource-group
                                      container-name]
                               :as opts}]
  (if (not (str/ends-with? container-name "*"))
    container-name
    (as-> ^{:out :string} ($ az container list -g ~resource-group --query (str "[?starts_with(name, '" (subs container-name 0 (dec (count container-name))) "')].name")) v
          (check v)
          (:out v)
          (json/parse-string v keyword)
          (vec v)
          (do (assert (= 1 (count v)) "expected to find a single container")
              v)
          (first v))))

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
          v
          (catch Exception e
            (assert false "could not resolve subscription id!")))))

(defn access-token [_]
  (as-> ^{:out :string} ($ az account get-access-token) v
        (check v)
        (:out v)
        (json/parse-string v keyword)
        (do
          (assert (= "Bearer" (:tokenType v)))
          (:accessToken v))))

(defn start-client! [{:keys [container-name
                             resource-group
                             subscription-id]
                      :as opts}]
  (assert (not-empty-string resource-group) ":resource-group must be specified")
  (let [container-name (resolve-container-name opts)
        subscription-id (or (not-empty-string subscription-id)
                            (resolve-subscription-id opts))]
    (assert (not-empty-string container-name) ":container-name or :container-name-starts-with must be specified")))


(comment
  (def opts {:resource-group "rg-stage-we"
             :container-name "aci-iretest*"})
  (start-client! opts))