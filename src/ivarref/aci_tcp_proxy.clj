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
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)))

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
                             proxy-path
                             remote-host
                             remote-port]
                      :as   opts}]
  (log/info "creating remote proxy at" proxy-path "...")
  (let [subscriptionId (get-subscription-id opts)
        resource-group resource-group
        container-group-name (get-container-name opts)
        container-name (get-container-name opts)
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
      (let [{:keys [webSocketUri password] :as body}
            (-> resp
                :body
                bs/to-string
                (json/decode keyword))
            _ (log/info "connecting to websocket URL" webSocketUri "...")
            remote-websocket @(http/websocket-client webSocketUri)]
        (log/info "entering password ...")
        @(s/put! remote-websocket password)
        @(s/put! remote-websocket (str remote-host "\n" remote-port "\n"))
        (log/info "got new websocket connection for" (str remote-host ":" remote-port))
        remote-websocket))))

(defn decode [str-chunk]
  (.decode (Base64/getMimeDecoder) ^String str-chunk))

(defn encode [byte-chunk]
  (.encodeToString (Base64/getMimeEncoder) ^"[B" byte-chunk))

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

(defn parse-byte [s]
  (when (string? s)
    (-> s
        (str/replace "$" "0")
        (str/replace "!" "1")
        (Integer/parseInt 2)
        (.byteValue))))

(comment
  (let [data (str/join (repeat (* 80 3) "a"))
        encoder (Base64/getMimeEncoder)
        decoder (Base64/getMimeDecoder)
        enc (.encodeToString encoder (.getBytes data StandardCharsets/UTF_8))
        lines (str/split-lines enc)
        decs (String.
               (.decode decoder (str/join "\n" lines))
               StandardCharsets/UTF_8)]
    (= decs data)))

(comment
  (let [check " !abcæøåðÿ"
        byt (seq (.getBytes check StandardCharsets/ISO_8859_1))]
    (doseq [byt byt]
      (let [bin-str (str/replace
                      (format "%8s" (Integer/toBinaryString (bit-and byt 0xff)))
                      " "
                      "0")
            parsed (parse-byte (-> bin-str
                                   (str/replace "0" "$")
                                   (str/replace "1" "!")))]
        (assert (= parsed byt))
        (println bin-str byt)))))

(defn proxy-handler [local remote]
  (log/info "setting up proxy between local socket and remote websocket")
  (add-close-handlers local remote)
  (let [send-count (atom 0)
        consume-byte! (fn [byte-str]
                        (if (= 8 (count byte-str))
                          (do
                            (log/debug "sending byte back to local client!")
                            (if @(s/put! local (byte-array [(parse-byte byte-str)]))
                              (do
                                (let [cnt (swap! send-count inc)]
                                  (when (= 0 (mod cnt 1024))
                                    (log/info "sent" cnt "bytes to local client")))
                                (log/debug "sent back a single byte!"))
                              (log/error "failed to send back a single byte!")))
                          (do
                            (log/warn "skipping incomplete byte consume!"))))]
    (s/consume
      (fn [byte-chunk]
        (log/info "pushing to remote:" (str/trim (encode byte-chunk)))
        (if @(s/put! remote (str (encode byte-chunk) "\n\n"))
          (log/info "ok push to remote")
          (log/error "could not push to remote")))
      local)

    (future
      (->> remote
           (s/->source)
           (s/mapcat (fn [x]
                       (cond (string? x)
                             (seq x)

                             (bytes? x)
                             (seq (String. ^"[B" x StandardCharsets/UTF_8))

                             :else
                             (do (log/error "unhandled type:" (class x))
                                 (log/error "x:" x)
                                 (throw (ex-info "unhandled type" {:x x}))))))
           (s/reduce (fn [o n]
                       (if (not (contains? #{\! \$} n))
                         (do
                           (log/debug "dropping" n)
                           o)
                         (let [o (str o n)]
                           (log/debug "consuming" n)
                           (if (= 8 (count o))
                             (do
                               (consume-byte! o)
                               "")
                             o))))
                     "")
           (deref)
           (consume-byte!))
      (log/info "remote is drained, closing")
      (s/close! local))))

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
                      :or   {port        0
                             bind        "127.0.0.1"
                             port-file   ".aci-port"
                             proxy-path  "/app/lib/Proxy"
                             remote-host "127.0.0.1"
                             remote-port 7777
                             block?      true}
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
        container-name (get-container-name opts)
        subscription-id (get-subscription-id opts)
        _access-token (access-token opts)]
    (assert (not-empty-string container-name) ":container-name or :container-name-starts-with must be specified")
    (log/info "starting aci tcp proxy server...")
    (let [server (tcp/start-server (fn [s info] (handler opts s info))
                                   {:socket-address (InetSocketAddress. ^String bind ^Integer port)})]
      (log/info "started proxy server on" (str bind "@" (netty/port server)))
      (log/info "remote is" (str remote-host ":" remote-port))
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