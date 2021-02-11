(ns ivarref.local-test
  (:require [aleph.http :as http]
            [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [clojure.tools.logging :as log]
            [babashka.process :as p :refer [$ check]])
  (:import (java.net InetSocketAddress ServerSocket InetAddress)
           (java.io InputStreamReader BufferedReader BufferedWriter OutputStreamWriter)
           (java.nio.charset StandardCharsets)
           (java.lang ProcessBuilder$Redirect)))

(defn echo-handler [s info]
  (log/info "new connection for echo handler")
  (s/connect s s))

#_(defonce
    log-server
    (future
      (let [ss (ServerSocket. 6666 10 (InetAddress/getLoopbackAddress))]
        (loop []
          (log/info "log server waiting for connection")
          (let [sock (.accept ss)]
            (log/info "got new connection on log server...")
            (future
              (let [lines (line-seq (BufferedReader. (InputStreamReader. (.getInputStream sock) StandardCharsets/UTF_8)))]
                (doseq [lin lines]
                  (log/info "line from proxy:" lin))))
            (recur))))))

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
    (log/info "launching runner ...")
    (let [pb (->
               (ProcessBuilder. ["/home/ire/code/infra/aci-tcp-proxy/Runner"])
               #_(.redirectError ProcessBuilder$Redirect/INHERIT))
          ^Process proc (.start pb)
          _ (log/info "launching runner ... OK")
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
    (log/info "launch java returned")
    (.write in "Hello From Clojure\n")
    (Thread/sleep 1000)
    (.close in)))

(defn ws-proxy-redir [ws]
  (log/info "launching proxy instance ...")
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
        (log/info "got >" chunk "< from websocket"))
      ws)
    (log/info "launching proxy instance ... OK!")))

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

(comment
  (let [ws @(http/websocket-client "ws://localhost:3333")]
    (log/info "got websocket client!")
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
    (s/close! ws)))