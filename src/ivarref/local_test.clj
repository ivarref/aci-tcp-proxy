(ns ivarref.local-test
  (:require [aleph.http :as http]
            [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [clojure.tools.logging :as log]
            [babashka.process :refer [$ check]]
            [clojure.string :as str]
            [ivarref.ws-server])
  (:import(java.nio.charset StandardCharsets)
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

(comment
  (ws-enc (.getBytes " !abcæøåðÿ" StandardCharsets/ISO_8859_1)))

(defn mime-reducer [cb so-far chr]
  (cond
    ; ignore echo from stdin on server
    (contains? #{\! \$} chr)
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
  (clear)
  (assert (bytes? byt))
  (let [p (promise)
        ws @(http/websocket-client "ws://localhost:3333")]
    (log/info "p is" promise)
    (log/debug "got websocket client!")
    (s/on-closed
      ws
      (fn [& args]
        (deliver p nil)
        (log/debug "websocket client closed")))
    (mime-consumer! ws (fn [chunk] (deliver p "whooho")))
    @(s/put! ws (ws-enc byt))
    (Thread/sleep 3000)
    (log/info "***********************")
    (let [res (deref p)]
      (log/info "res is" res)
      @(s/close! ws)
      (= (seq res) (seq byt)))))

(comment
  (test-round-trip (.getBytes "Hello World!" StandardCharsets/UTF_8)))


(defn run-test []
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
                                     (log/info "ws client got >" (String. decoded StandardCharsets/UTF_8) "<")
                                     "")

                                   :else
                                   (str o n)))
                               ""))]
      @(s/put! ws (ws-enc (.getBytes " !abcæøåðÿ" StandardCharsets/UTF_8)))
      (log/info "ws client OK put")
      (log/info "waiting for socket to close..."))))
      ;@drain
      ;(s/close! ws))))

(comment
  (run-test))
