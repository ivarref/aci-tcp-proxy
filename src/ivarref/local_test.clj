(ns ivarref.local-test
  (:require [aleph.http :as http]
            [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [clojure.tools.logging :as log]
            [babashka.process :refer [$ check]]
            [clojure.string :as str]
            [ivarref.az-utils :as az-utils]
            [ivarref.ws-utils :as wu]
            [ivarref.ws-server])
  (:import (java.nio.charset StandardCharsets)
           (java.util Base64)))

(defn clear []
  (.print System/out "\033[H\033[2J")
  (.flush System/out))

(defn test-round-trip [ws byt]
  (assert (bytes? byt))
  (let [start-time (System/currentTimeMillis)
        chunks (atom [])
        p (promise)]
    (log/debug "got websocket client!")
    (s/on-closed
      ws
      (fn [& args]
        (deliver p nil)
        (log/debug "websocket client closed")))
    (wu/mime-consumer! ws (fn [byte-chunk]
                            (let [new-chunks (swap! chunks conj byte-chunk)]
                              (when (= (alength byt)
                                       (reduce + 0 (map alength new-chunks)))
                                (deliver p (byte-array (mapcat seq new-chunks)))))))
    @(s/put! ws (wu/ws-map {:host "127.0.0.1" :port "2222" :logPort "12345"}))
    (doseq [chunk (partition-all 4096 (seq byt))]
      (log/info "pushing chunk of" (count chunk) "bytes")
      @(s/put! ws (wu/ws-enc (byte-array (vec chunk)))))
    @p
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
    ;(clear)
    (test-round-trip (local-websocket)
                     (.getBytes "Hello World !abcæøåðÿ!" StandardCharsets/UTF_8))))
