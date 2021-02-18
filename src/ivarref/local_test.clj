(ns ivarref.local-test
  (:require [aleph.http :as http]
            [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [clojure.tools.logging :as log]
            [babashka.process :refer [$ check]]
            [clojure.string :as str]
            [ivarref.az-utils :as az-utils]
            [ivarref.ws-utils :as wu]
            [ivarref.ws-server]
            [clojure.core.async :as async])
  (:import (java.nio.charset StandardCharsets)))

(defn clear []
  (.print System/out "\033[H\033[2J")
  (.flush System/out))

(defn test-round-trip [ws byt]
  (assert (bytes? byt))
  (let [start-time (System/currentTimeMillis)
        chunks (atom [])
        p (promise)
        push-ready (async/chan)]
    (log/debug "got websocket client!")
    (s/on-closed
      ws
      (fn [& args]
        (deliver p nil)
        (log/debug "websocket client closed")))
    (wu/mime-consumer!
      ws
      (partial wu/handle-server-op push-ready)
      (fn [byte-chunk]
        (assert (bytes? byte-chunk))
        (let [new-chunks (swap! chunks conj byte-chunk)
              new-length (reduce + 0 (mapv alength new-chunks))
              percentage (double (/ (* 100 new-length) (alength byt)))]
          (log/info "received byte chunk of length" (alength byte-chunk)
                    ","
                    (format "%.1f%%" percentage) "done! Total so far:" new-length)
          (when (= (alength byt) new-length)
            (log/info "delivering...")
            (deliver p (byte-array (mapcat seq new-chunks)))))))
    (log/info "client waiting for push-ready...")
    (async/<!! push-ready)
    (log/info "client waiting for push-ready... OK!")
    @(s/put! ws (wu/ws-map {:host "127.0.0.1" :port "2222" :logPort "12345"}))
    (log/info "pushing a total of" (count (seq byt)) "bytes ...")
    (doseq [chunk (partition-all 1024 (seq byt))]
      (assert (true? @(s/put! ws (wu/ws-enc (byte-array (vec chunk))))))
      (async/<!! push-ready))
    (log/info "done pushing!")
    @p
    (log/info "got all chunks, closing!")
    @(s/put! ws (wu/ws-enc-remote-cmd "close!"))
    (s/close! ws)
    (assert (= (seq @p) (seq byt))
            "round trip test failed!")
    (log/info "number of bytes:" (alength byt))
    (let [spent-time (- (System/currentTimeMillis) start-time)
          bytes-by-ms (double (/ (alength byt) spent-time))]
      (log/info "round trip test OK in" spent-time "ms, " (format "%.1f" bytes-by-ms)
                "kB/s! \uD83D\uDE3A \uD83D\uDE3B"))))

(defn az-websocket []
  (az-utils/get-websocket {:resource-group "rg-stage-we"
                           :proxy-path     "/app/lib/Proxy"
                           :container-name "aci-iretest*"}))

(defn local-websocket []
  @(http/websocket-client "ws://localhost:3333"))


(comment
  (do
    (test-round-trip
      (az-websocket)
      (.getBytes
        (str/join "\n" (repeat 40000 "Hello World !abcæøåðÿ!"))
        StandardCharsets/UTF_8))))
