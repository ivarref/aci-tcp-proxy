(ns ivarref.local-test
  (:require [aleph.http :as http]
            [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [clojure.tools.logging :as log]
            [babashka.process :refer [$ check]]
            [clojure.string :as str])
  (:import (java.net InetSocketAddress)
           (java.io InputStreamReader BufferedReader BufferedWriter OutputStreamWriter)
           (java.nio.charset StandardCharsets)
           (java.lang ProcessBuilder$Redirect)
           (java.util Base64)))

(defn clear []
  (.print System/out "\033[H\033[2J")
  (.flush System/out))

(defn echo-handler [s info]
  (log/info "new connection for echo handler")
  (s/consume
    (fn [byt]
      (assert (bytes? byt))
      (log/info "echo handler received chunk of" (alength byt) "bytes")
      (s/put! s byt))
    s))

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
    (let [start-time (System/currentTimeMillis)
          pb (->
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
      (log/info "waiting for server to emit a single line... :-)")
      (.readLine stdout)
      (let [spent-time (- (System/currentTimeMillis) start-time)]
        (log/info "proxy ready in" spent-time "ms"))
      (future
        (doseq [lin (line-seq stdout)]
          (consume-stdout lin))
        (log/debug "proxy stdout exhausted"))
      {:in in})))

(defn ws-proxy-redir [ws]
  (log/debug "launching proxy instance ...")
  (let [{:keys [in]} (launch-java-file "src/Proxy.java"
                                       {:consume-stdout
                                        (fn [lin]
                                          (log/info "ws server got line from proxy:" lin)
                                          (s/put! ws (str lin "#\n")))})]
    (s/on-closed ws
                 (fn [& args]
                   (log/debug "websocket closed, closing proxy")
                   (.close in)))
    (s/consume
      (fn [chunk]
        (assert (string? chunk))
        (log/info "websocket server: got byte chunk from client of length" (int (/ (count chunk)
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

(defn ws-enc [byt]
  (assert (bytes? byt))
  (let [sb (StringBuilder.)]
    (doseq [b (seq byt)]
      (let [byte-bin-str (-> (format "%8s" (Integer/toBinaryString (bit-and b 0xff)))
                             (str/replace " " "0")
                             (str/replace "0" "_")
                             (str/replace "1" "!"))]
        (.append sb byte-bin-str)
        (.append sb "\n")))
    (.append sb "$\n")
    (.toString sb)))

(comment
  (ws-enc (.getBytes " !abcæøåðÿ" StandardCharsets/ISO_8859_1)))

(comment
  (do
    (clear)
    (let [ws @(http/websocket-client "ws://localhost:3333")]
      (log/debug "got websocket client!")
      (s/on-closed
        ws
        (fn [& args]
          (log/debug "websocket client closed")))
      (let [drain (->> ws
                       (s/->source)
                       (s/mapcat (fn [x]
                                   (assert (string? x))
                                   (seq x)))
                       (s/reduce (fn [o n]
                                   (cond
                                     (contains? #{\! \$} n)
                                     o

                                     (= n \#)
                                     (let [decoded (.decode (Base64/getMimeDecoder) o)]
                                       (log/info "ws client got" (String. decoded StandardCharsets/UTF_8))
                                       "")

                                     :else
                                     (str o n)))
                                 ""))]
        @(s/put! ws (ws-enc (.getBytes "Hello world from websocket!" StandardCharsets/UTF_8)))
        (log/info "ws client OK put")
        @drain
        (s/close! ws)))))

(comment
  (let [{:keys [in]} (launch-java-file
                       "src/Hello.java"
                       {:consume-stdout (fn [lin] (log/info "got stdout:" lin))})]
    (.write in "Hello From Clojure\n")
    (Thread/sleep 1000)
    (.close in)))