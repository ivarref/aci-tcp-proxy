(ns ivarref.local-test
  (:require [aleph.http :as http]
            [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [clojure.tools.logging :as log]
            [babashka.process :refer [$ check]])
  (:import (java.net InetSocketAddress)
           (java.io InputStreamReader BufferedReader BufferedWriter OutputStreamWriter)
           (java.nio.charset StandardCharsets)
           (java.lang ProcessBuilder$Redirect)))

(defn echo-handler [s info]
  (log/debug "new connection for echo handler")
  (s/connect s s))

(defonce
  echo-server
  (tcp/start-server
    (fn [s info]
      (echo-handler s info))
    {:socket-address (InetSocketAddress. "127.0.0.1" 2222)}))

(defn launch-java-file [f {:keys [consume-stdout]}]
  (let [new-src (str "#!/usr/bin/java --source 11\n\n" (slurp f))]
    (spit "Runner" new-src)
    (check ($ chmod +x Runner))
    (log/debug "launching runner ...")
    (let [pb (->
               (ProcessBuilder. ["/home/ire/code/infra/aci-tcp-proxy/Runner"])
               #_(.redirectError ProcessBuilder$Redirect/INHERIT))
          ^Process proc (.start pb)
          _ (log/debug "launching runner ... OK")
          in (BufferedWriter. (OutputStreamWriter. (.getOutputStream proc) StandardCharsets/UTF_8))
          stdout (BufferedReader. (InputStreamReader. (.getInputStream proc) StandardCharsets/UTF_8))]
      (future
        (doseq [lin (line-seq (BufferedReader. (InputStreamReader. (.getErrorStream proc) StandardCharsets/UTF_8)))]
          (log/info "proxy:" lin))
        (log/debug "proxy stderr exhausted"))
      (future
        (doseq [lin (line-seq stdout)]
          (consume-stdout lin))
        (log/debug "proxy stdout exhausted"))
      {:in in})))

(comment
  (let [{:keys [in]} (launch-java-file
                       "src/Hello.java"
                       {:consume-stdout (fn [lin] (log/info "got stdout:" lin))})]
    (.write in "Hello From Clojure\n")
    (Thread/sleep 1000)
    (.close in)))

(defn ws-proxy-redir [ws]
  (log/debug "launching proxy instance ...")
  (let [{:keys [in]} (launch-java-file "src/Proxy.java"
                                       {:consume-stdout
                                        (fn [lin]
                                          (log/info "got line:" lin)
                                          (s/put! ws (str lin "\n")))})]
    (s/on-closed ws
                 (fn [& args]
                   (log/info "websocket closed, closing proxy")
                   (.close in)))
    (s/consume
      (fn [chunk]
        (log/info "websocket server: got >" chunk "< from client"))
      ws)
    (log/debug "launching proxy instance ... OK!")))

(defn ws-handler [req]
  (let [ws @(http/websocket-connection req)]
    (s/on-closed
      ws
      (fn [& args] (log/info "websocket closed")))
    (log/info "new connection for ws handler")
    (ws-proxy-redir ws)))

(defonce
  local-ws-server
  (http/start-server
    (fn [req] (ws-handler req))
    {:socket-address (InetSocketAddress. "127.0.0.1" 3333)}))

(defn clear []
  (.print System/out "\033[H\033[2J")
  (.flush System/out))

(comment
  (do
    (clear)
    (let [ws @(http/websocket-client "ws://localhost:3333")]
      (log/debug "got websocket client!")
      (s/on-closed
        ws
        (fn [& args]
          (log/info "websocket client closed")))
      (s/consume
        (fn [chunk]
          (log/info "!!! client got chunk" chunk))
        ws)
      (s/put! ws "Hello from websocket!")
      (Thread/sleep 3000)
      (s/close! ws))))