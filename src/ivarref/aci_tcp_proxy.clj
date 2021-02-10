(ns ivarref.aci-tcp-proxy
  (:require [aleph.tcp :as tcp]
            [babashka.process :refer [$ check]]
            [clojure.walk :as walk]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [aleph.netty :as netty]
            [aleph.http :as http]
            [manifold.stream :as s]
            [byte-streams :as bs])
  (:import (java.util UUID Base64)
           (java.net InetSocketAddress)))

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

(defn access-token [_]
  (as-> ^{:out :string} ($ az account get-access-token) v
        (check v)
        (:out v)
        (json/parse-string v keyword)
        (do
          (assert (= "Bearer" (:tokenType v)))
          (:accessToken v))))

(defn get-websocket [{:keys [resource-group
                             proxy-path
                             remote-host
                             remote-port]
                      :as opts}]
  (log/info "creating remote proxy at" proxy-path "...")
  (let [subscriptionId (resolve-subscription-id opts)
        resource-group resource-group
        container-group-name (resolve-container-name opts)
        container-name (resolve-container-name opts)
        token (access-token opts)
        ; POST https://management.azure.com/subscriptions/{subscriptionId}/resourceGroups/{resourceGroupName}/providers/Microsoft.ContainerInstance/containerGroups/{containerGroupName}/containers/{containerName}/exec?api-version=2019-12-01
        url (str "https://management.azure.com/subscriptions/"
                 subscriptionId
                 "/resourceGroups/"
                 resource-group
                 "/providers/Microsoft.ContainerInstance/containerGroups/"
                 container-group-name
                 "/containers/"
                 container-name
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
      (do
        (log/info "connecting to websocket...")
        (let [{:keys [webSocketUri password] :as body}
              (-> resp
                  :body
                  bs/to-string
                  (json/decode keyword))
              sock @(http/websocket-client webSocketUri)]
          (log/info "entering password ...")
          @(s/put! sock password)
          @(s/put! sock (str remote-host "\n" remote-port "\n"))
          (log/info "got new websocket connection for" remote-host ":" remote-port)
          sock)))))

(defn decode [str-chunk]
  (.decode (Base64/getMimeDecoder) ^String str-chunk))

(defn encode [byte-chunk]
  (.encode (Base64/getMimeEncoder) ^"[B" byte-chunk))

(defn add-close-handlers [local remote]
  (s/on-closed
   local
   (fn [& args]
     (log/info "local client closed connection")
     (s/close! remote)))

  (s/on-closed
    remote
    (fn [& args]
      (log/info "remote closed connection")
      (s/close! local))))

(defn proxy-handler [local remote]
  (log/info "setting up proxy between local socket and remote websocket")
  (add-close-handlers local remote)
  (let [consume-base64-chunk!
        (fn [[_ str-chunk]]
          (log/info "sending to local client")
          (s/put! local (decode str-chunk)))]

    (s/consume
      (fn [chunk]
        (log/info "pushing to remote...")
        (s/put! remote (str (encode chunk) "\n")))
      local)

    (->> remote
         (s/mapcat seq)
         (s/reduce (fn [[prev o] n]
                     (log/info (pr-str "consume from remote..." n))
                     (if (and (= \return prev) (= \newline n))
                       (do (consume-base64-chunk! [n o])
                           [n ""])
                       [n (str o n)]))
                   ["" ""])
         (deref)
         (consume-base64-chunk!))
    (log/info "remote is drained, closing")
    (s/close! local)))

(defn handler [opts sock _info]
  (log/info "starting new connection ...")
  (if-let [websock (get-websocket opts)]
    (proxy-handler sock websock)
    (do
      (log/error "could not get websocket, aborting!")
      (s/close! sock))))

(defn start-client! [{:keys [port
                             port-file
                             bind
                             resource-group
                             block?
                             proxy-path
                             remote-host
                             remote-port]
                      :or   {port      8888
                             bind      "127.0.0.1"
                             port-file ".aci-port"
                             proxy-path "/app/lib/Proxy"
                             remote-host "127.0.0.1"
                             remote-port 7777
                             block?    true}
                      :as   opts}]
  (Thread/setDefaultUncaughtExceptionHandler
    (reify Thread$UncaughtExceptionHandler
      (uncaughtException [_ thread ex]
        (log/error ex "Uncaught exception on" (.getName thread))
        (log/error "error message was:" (ex-message ex)))))

  (assert (not-empty-string resource-group) ":resource-group must be specified")
  (let [opts (-> opts
               (update :proxy-path (fn [o] (or o proxy-path)))
               (update :remote-host (fn [o] (or o remote-host)))
               (update :remote-port (fn [o] (or o remote-port))))
        container-name (resolve-container-name opts)
        subscription-id (resolve-subscription-id opts)
        _access-token (access-token opts)]
    (assert (not-empty-string container-name) ":container-name or :container-name-starts-with must be specified")
    (log/info "starting aci tcp proxy server...")
    (let [server (tcp/start-server (fn [s info] (handler opts s info))
                                   {:socket-address (InetSocketAddress. ^String bind ^Integer port)})]
      (log/info "started proxy server on" (str bind "@" (netty/port server)))
      (spit port-file (netty/port server))
      (log/info "wrote port" (netty/port server) "to" port-file)
      (.addShutdownHook
        (Runtime/getRuntime)
        (Thread.
          ^Runnable (fn []
                      (try
                        (log/info "shutting down server")
                        (.close server)
                        (catch Throwable t
                          (log/warn t "error during closing server"))))))
      (when block?
        @(promise)))))



(comment
  (def opts {:resource-group "rg-stage-we"
             :container-name "aci-iretest*"})
  (start-client! opts))