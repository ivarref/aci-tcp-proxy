(ns ivarref.ws-utils
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [manifold.stream :as s])
  (:import (java.nio.charset StandardCharsets)
           (java.util Base64)))

(defn ws-enc-inner [byt remote-cmd?]
  (assert (bytes? byt))
  (let [sb (StringBuilder.)]
    (doseq [b (seq byt)]
      (let [byte-bin-str (-> (format "%8s" (Integer/toBinaryString (bit-and b 0xff)))
                             (str/replace " " "0")
                             (str/replace "0" "_")
                             (str/replace "1" "!"))]
        (.append sb byte-bin-str)
        (.append sb "\n")))
    (when remote-cmd?
      (.append sb "$"))
    (.append sb "$\n")
    (.toString sb)))

(defn ws-enc [byt]
  (ws-enc-inner byt false))

(defn ws-enc-remote-cmd [cmd]
  (assert (string? cmd))
  (ws-enc-inner (.getBytes cmd StandardCharsets/UTF_8) true))

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

(defn mime-reducer [srv-op-cb cb so-far chr]
  (log/debug "received" (pr-str chr))
  (cond
    ; ignore echo from stdin on server
    (contains? #{\! \$ \_} chr)
    so-far

    ; the char # marks end of mime chunk
    (= chr \#)
    (let [decoded (.decode (Base64/getMimeDecoder) ^String so-far)]
      (log/debug "consuming" (alength decoded) "bytes...")
      (try
        (cb decoded)
        (catch Throwable t
          (log/error t "error in mime-consumer cb")
          (log/error "error message was:" (ex-message t))))
      "")

    (= chr \^)
    (let [decoded (.decode (Base64/getMimeDecoder) ^String so-far)]
      (log/debug "consuming" (alength decoded) "bytes...")
      (try
        (srv-op-cb (String. decoded StandardCharsets/UTF_8))
        (catch Throwable t
          (log/error t "error in mime-consumer srv-op-cb")
          (log/error "error message was:" (ex-message t))))
      "")

    ; build up mime chunk
    :else
    (do
      (str so-far chr))))

(defn mime-consumer! [ws srv-cb cb]
  (->> ws
       (s/->source)
       (s/mapcat (fn [x]
                   (assert (string? x))
                   (seq x)))
       (s/reduce (partial mime-reducer srv-cb cb) "")))

