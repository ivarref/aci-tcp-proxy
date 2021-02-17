(ns ivarref.aci-tcp-proxy
  (:require [aleph.tcp :as tcp]
            [babashka.process :refer [$ check]]
            [clojure.tools.logging :as log]
            [ivarref.az-utils :as az-utils]
            [aleph.netty :as netty]
            [manifold.stream :as s]
            [ivarref.ws-utils :as wu])
  (:import (java.net InetSocketAddress)))

(defn not-empty-string [s]
  (and (string? s)
       (not-empty s)))

(defn add-close-handlers [local remote]
  (s/on-closed
    local
    (fn [& args]
      (log/info "local client closed connection")
      @(s/put! remote (wu/ws-enc-remote-cmd "close!"))
      (s/close! remote)))

  (s/on-closed
    remote
    (fn [& args]
      (log/info "remote closed connection")
      (s/close! local))))

(defn proxy-handler [local ws]
  (log/info "setting up proxy between local socket and remote websocket")
  (add-close-handlers local ws)
  (s/consume
    (fn [byt]
      (assert (bytes? byt))
      @(s/put! ws (wu/ws-enc byt)))
    local)
  (wu/mime-consumer!
    ws
    (fn [byte-chunk]
      @(s/put! local byte-chunk))))

(defn handler [{:keys [remote-host remote-port] :as opts} sock _info]
  (log/info "starting new connection ...")
  (if-let [websock (az-utils/get-websocket opts)]
    (do
      (log/info "established, configuring ...")
      @(s/put! websock (wu/ws-map {:host    remote-host
                                   :port    (str remote-port)
                                   :logPort "12345"}))
      (proxy-handler sock websock))
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
                      :or   {port        7890
                             bind        "127.0.0.1"
                             port-file   ".aci-port"
                             proxy-path  "/app/lib/Proxy"
                             remote-host "127.0.0.1"
                             remote-port 9999
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
                 (update :remote-port (fn [o] (or o remote-port))))]
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