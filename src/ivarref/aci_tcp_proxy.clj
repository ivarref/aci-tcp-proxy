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

(defn handler [{:keys [remote-host remote-port] :as opts} sock _info]
  (log/info "starting new connection ...")
  (if-let [websock (delay (az-utils/get-websocket opts))]
    (do
      (wu/redir-handler sock websock {:host    remote-host
                                      :port    (str remote-port)
                                      :logPort "12345"}))
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