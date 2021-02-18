(ns ivarref.ws-server
  (:require [aleph.http :as http]
            [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [clojure.tools.logging :as log]
            [babashka.process :refer [$ check]]
            [ivarref.proxy :as proxy])
  (:import (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)))

(defn echo-handler [s info]
  (log/debug "new connection for echo handler")
  (s/consume
    (fn [byt]
      (assert (bytes? byt))
      (log/info "echo handler received chunk of" (alength byt) "bytes")
      (assert (true? @(s/put! s byt)))
      (log/info "sleeping...")
      (Thread/sleep 3000)
      (log/info "sleeping... done"))
    s))

(defonce
  echo-server
  (tcp/start-server
    (fn [s info]
      (echo-handler s info))
    {:socket-address (InetSocketAddress. "127.0.0.1" 2222)}))


(defn log-server-handler [sock]
  (->> sock
       (s/mapcat seq)
       (s/reduce (fn [so-far byt]
                   (if (= 10 byt)
                     (do
                       (log/debug "log-server:" (String. (byte-array so-far) StandardCharsets/UTF_8))
                       [])
                     (conj so-far byt)))
                 [])))

(defonce
  log-server
  (tcp/start-server
    (fn [s info]
      (log-server-handler s))
    {:socket-address (InetSocketAddress. "127.0.0.1" 12345)}))

(defn ws-proxy-redir [ws]
  (log/debug "launching proxy instance ...")
  (let [{:keys [in]} (proxy/launch-java-file "src/Proxy.java"
                                             {:consume-stdout
                                              (fn [lin]
                                                #_(log/info "got line from proxy:" lin)
                                                (assert (true? @(s/put! ws (str lin "\n")))))})]
    (s/on-closed ws
                 (fn [& args]
                   (log/debug "websocket closed, closing proxy")
                   (.close in)))
    (s/consume
      (fn [chunk]
        (assert (string? chunk))
        #_(log/info "got str byte chunk from client of length" (int (/ (count chunk)
                                                                       9)))
        ; write chunk back to client like in a terminal echo
        @(s/put! ws chunk)
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
