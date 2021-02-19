(ns ivarref.ws-utils
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [manifold.stream :as s]
            [clojure.core.async :as async])
  (:import (java.nio.charset StandardCharsets)
           (java.util Base64)))

(def alphabet (mapv str (seq "_!@%'&*([{}]).,;")))

(defn to-hex-nibble [b]
  (nth alphabet b))

(defn ws-enc-inner [byt remote-cmd?]
  (assert (bytes? byt))
  (let [sb (StringBuilder.)]
    (doseq [chunk (partition-all 38 (seq byt))]
      (doseq [byt chunk]
        (let [byt (bit-and 0xff byt)]
          (.append sb ^String (to-hex-nibble (bit-shift-right byt 4)))
          (.append sb ^String (to-hex-nibble (bit-and 0xf byt)))))
      (.append sb "\n"))
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

(defn handle-server-op [ready-chan server-op!]
  (cond
    (= "ready!" server-op!)
    (async/>!! ready-chan :ready)

    (= "chunk-ok" server-op!)
    (do
      (log/debug "got chunk-ok!")
      (async/>!! ready-chan :chunk-ok))

    :else
    (log/warn "unhandled server-op" server-op!)))


(defn push-loop [push-lock push-ready pending-chunks ws pending-counter]
  (if-let [byt (async/<!! pending-chunks)]
    (do
      (swap! pending-counter dec)
      (doseq [chunk (partition-all 1024 (seq byt))]
        (locking push-lock
          (assert (true? @(s/put! ws (ws-enc (byte-array (vec chunk))))))
          (async/<!! push-ready))
        (log/info "pushed chunk of length" (alength chunk) "to remote and received ack"))
      (recur push-lock push-ready pending-chunks ws pending-counter))
    (do
      (log/info "push-loop exiting"))))

(defn redir-handler [local ws-delayed config]
  (let [push-ready (async/chan)
        pending-chunks (async/chan 10000)
        pending-counter (atom 0)
        push-lock (Object.)]
    (log/info "setting up local consumer...")
    (s/consume
      (fn [byte-chunk]
        (assert (bytes? byte-chunk))
        (let [pending-cnt (swap! pending-counter inc)]
          (log/info "pending counter:" pending-cnt))
        (async/>!! pending-chunks byte-chunk))
      local)
    (s/on-closed local (fn [& args]
                         (locking push-lock
                           @(s/put! @ws-delayed (ws-enc-remote-cmd "close!")))
                         (async/close! pending-chunks)))
    (log/info "dereffing websocket")
    (let [ws @ws-delayed]
      (s/on-closed ws (fn [& args] (async/close! pending-chunks)))
      (mime-consumer! ws
                      (partial handle-server-op push-ready)
                      (fn [byte-chunk]
                        (assert (bytes? byte-chunk))
                        (log/info "received chunk of" (alength byte-chunk) "from remote")
                        (if (false? @(s/put! local byte-chunk))
                          (log/error "could not push byte chunk to local"))))
      (log/info "waiting for remote ready...")
      (async/<!! push-ready)
      @(s/put! ws (ws-map config))
      (log/info "pushed config!")
      (log/info "push loop starting...")
      (future (push-loop push-lock push-ready pending-chunks ws pending-counter)))))
