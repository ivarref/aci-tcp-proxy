(ns ivarref.local-test
  (:require [aleph.http :as http]
            [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [clojure.tools.logging :as log]
            [babashka.process :refer [$ check]]
            [clojure.string :as str]
            [ivarref.ws-server])
  (:import (java.nio.charset StandardCharsets)
           (java.util Base64)))

(defn clear []
  (.print System/out "\033[H\033[2J")
  (.flush System/out))

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

(defn ws-map [m]
  (assert (map? m))
  (-> (reduce-kv (fn [o k v]
                   (str o
                        (if (keyword? k) (name k) (str k))
                        "="
                        (str v)
                        "\n"))
                 ""
                 m)
      (str/trim)
      (.getBytes StandardCharsets/UTF_8)
      (ws-enc)))

(comment
  (ws-enc (.getBytes " !abcæøåðÿ" StandardCharsets/ISO_8859_1)))

(defn mime-reducer [cb so-far chr]
  (cond
    ; ignore echo from stdin on server
    (contains? #{\! \$ \_} chr)
    so-far

    ; the char # marks end of mime chunk
    (= chr \#)
    (let [decoded (.decode (Base64/getMimeDecoder) ^String so-far)]
      (log/info "consuming" (alength decoded) "bytes...")
      (cb decoded)
      "")

    ; build up mime chunk
    :else
    (str so-far chr)))

(defn mime-consumer! [ws cb]
  (->> ws
       (s/->source)
       (s/mapcat (fn [x]
                   (assert (string? x))
                   (seq x)))
       (s/reduce (partial mime-reducer cb) "")))

(defn test-round-trip [byt]
  (assert (bytes? byt))
  (let [chunks (atom [])
        p (promise)
        ws @(http/websocket-client "ws://localhost:3333")]
    (log/debug "p is" promise)
    (log/debug "got websocket client!")
    (s/on-closed
      ws
      (fn [& args]
        (deliver p nil)
        (log/debug "websocket client closed")))
    (mime-consumer! ws (fn [byte-chunk]
                         (let [new-chunks (swap! chunks conj byte-chunk)]
                           (when (= (alength byt)
                                    (reduce + 0 (map alength new-chunks)))
                             (deliver p (byte-array (mapcat seq new-chunks)))))))
    @(s/put! ws (ws-map {:host "127.0.0.1" :port "2222"}))
    (doseq [chunk (partition-all 1024 (seq byt))]
      @(s/put! ws (ws-enc (byte-array (vec chunk)))))
    @p
    (s/close! ws)
    (assert (= (seq @p) (seq byt))
            "round trip test failed!")
    (log/info "number of bytes:" (alength byt))
    (log/info "round trip test OK! \uD83D\uDE3A \uD83D\uDE3B")))

(comment
  (test-round-trip (.getBytes (str/join "\n" (repeat 1000 "Hello World !abcæøåðÿ!"))
                              StandardCharsets/UTF_8)))
