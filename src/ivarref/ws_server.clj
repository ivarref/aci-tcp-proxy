(ns ivarref.ws-server
  (:require [aleph.http :as http]
            [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [clojure.tools.logging :as log]
            [babashka.process :refer [$ check]]
            [ivarref.runner :as runner])
  (:import (java.net InetSocketAddress)
           (java.io OutputStreamWriter InputStreamReader BufferedReader BufferedWriter)
           (java.nio.charset StandardCharsets)))

(defn echo-handler [s info]
  (log/info "new connection for echo handler")
  (s/consume
    (fn [byt]
      (assert (bytes? byt))
      (log/info "echo handler received chunk of" (alength byt) "bytes")
      @(s/put! s byt))
    s))

(defonce
  echo-server
  (tcp/start-server
    (fn [s info]
      (echo-handler s info))
    {:socket-address (InetSocketAddress. "127.0.0.1" 2222)}))

(defn ws-proxy-redir [ws]
  (log/debug "launching proxy instance ...")
  (let [{:keys [in]} (runner/launch-java-file "src/Proxy.java"
                                              {:consume-stdout
                                               (fn [lin]
                                                 (log/info "got line from proxy:" lin)
                                                 (s/put! ws (str lin "#\n")))})]
    (s/on-closed ws
                 (fn [& args]
                   (log/debug "websocket closed, closing proxy")
                   (.close in)))
    (s/consume
      (fn [chunk]
        (assert (string? chunk))
        (log/info "got str byte chunk from client of length" (int (/ (count chunk)
                                                                     9)))
        (.write in chunk)
        (.flush in))
      ws)
    (log/debug "launching proxy instance ... OK!")))

(defn ws-handler [req]
  (let [ws @(http/websocket-connection req)]
    (s/on-closed
      ws
      (fn [& args] (log/debug "websocket closed")))
    (log/debug "new connection for ws handler")
    (ws-proxy-redir ws)))

(defonce
  local-ws-server
  (http/start-server
    (fn [req] (ws-handler req))
    {:socket-address (InetSocketAddress. "127.0.0.1" 3333)}))
