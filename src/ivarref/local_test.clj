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

(defn launch-java-file [f stdout-line-cb]
  (let [new-src (str "#!/usr/bin/java --source 11\n\n" (slurp f))]
    (spit "Runner" new-src)
    (check ($ chmod +x Runner))
    (log/info "launching runner ...")
    (let [pb (->
               (ProcessBuilder. ["/home/ire/code/infra/aci-tcp-proxy/Runner"]))
               ;(.redirectError ProcessBuilder$Redirect/INHERIT))
          ^Process proc (.start pb)
          _ (log/info "launching runner ... OK")
          in (BufferedWriter. (OutputStreamWriter. (.getOutputStream proc) StandardCharsets/UTF_8))
          stdout (BufferedReader. (InputStreamReader. (.getInputStream proc) StandardCharsets/UTF_8))
          stderr (BufferedReader. (InputStreamReader. (.getErrorStream proc) StandardCharsets/UTF_8))]
      (future
        (doseq [lin (line-seq stderr)]
          (log/info "remote stderr:" lin))
        (log/info "remote stderr exhausted"))
      (future
        (doseq [lin (line-seq stdout)]
          (stdout-line-cb lin))
        (log/info "remote stdout exhausted"))
      {:in in})))

(comment
  (let [{:keys [in]} (launch-java-file "src/Hello.java")]
    (log/info "launch java returned")
    (Thread/sleep 1000)
    (.close in)))


(defn ws-proxy-redir [ws]
  (log/info "launching proxy instance ...")
  (let [{:keys [stdout]} (launch-java-file "src/Proxy.java")]
    (log/info "launching proxy instance ... OK!")
    (future
      (doseq [lin stdout]
        (log/info "pusing stdout line" lin)
        (s/put! ws (str lin "\n"))))))


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
  (let [conn @(http/websocket-client "ws://localhost:3333")]
    (log/info "got websocket client!")
    (s/on-closed
      conn
      (fn [& args]
        (log/info "websocket client closed")))
    (s/consume
      (fn [chunk]
        (log/info "client got chunk" chunk))
      conn)))