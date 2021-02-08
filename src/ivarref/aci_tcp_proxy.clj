(ns ivarref.aci-tcp-proxy
  (:gen-class)
  (:require [clojure.java.io :as io])
  (:import (java.io OutputStream InputStream BufferedInputStream BufferedOutputStream Closeable)
           (java.net Socket)
           (sun.misc Signal SignalHandler)))

(defn install!
  ([] (install! nil))
  ([dest-path]
   (with-open [in (io/input-stream (io/resource "ivarref/aci-tcp-proxy/proxy"))]
     (io/copy in (io/file (str dest-path "/proxy"))))))

(defn ^Socket socket [^String host ^long port]
  (Socket. host port))

(defn debug [^String s]
  (binding [*out* *err*]
    #_(println s)))

(def pipe-state
  (volatile! nil))

(defn handle-pipe! []
  (Signal/handle
    (Signal. "PIPE")
    (reify SignalHandler
      (handle [_ _]
        (debug ":PIPE")
        (vreset! pipe-state :PIPE)))))

(defn close!
  [^Closeable x]
  (when x
    (try
      (.close x)
      (catch Exception e
        nil))))

(defn read-stdin [^InputStream in ^OutputStream to-socket]
  (debug "reading from System/in ...")
  (try
    (let [buf (byte-array 1024)
          run? (atom true)]
      (while @run?
        (let [num-bytes (.read in buf)]
          (if (not= -1 num-bytes)
            (do
              (.write to-socket buf 0 num-bytes)
              (.flush to-socket))
            (do
              (reset! run? false)))))
      (debug "done reading from System/in"))
    (catch Exception e
      (debug (str "error during reading System/in: " (ex-message e))))))

(defn read-socket [^OutputStream out ^InputStream from-socket]
  (debug "reading from socket ...")
  (try
    (let [buf (byte-array 1024)
          run? (atom true)]
      (while @run?
        (let [num-bytes (.read from-socket buf)]
          (if (not= -1 num-bytes)
            (do
              (.write out buf 0 num-bytes)
              (.flush out))
            (do
              (debug "reading from socket closed!")
              (reset! run? false)))))
      (debug "done reading from socket"))
    (catch Exception e
      (debug (str "error during reading from socket: " (ex-message e))))))

(defn -main [& args]
  (handle-pipe!)
  (debug "starting proxy ...")
  (let [in (BufferedInputStream. System/in)
        out (BufferedOutputStream. System/out)
        sock (socket "127.0.0.1" 7777)
        to-socket (-> sock
                      ^OutputStream (.getOutputStream)
                      (BufferedOutputStream.))
        from-socket (-> sock
                        ^InputStream (.getInputStream)
                        (BufferedInputStream.))]
    (debug "starting proxy ... OK")

    (let [read-stdin (future (read-stdin in to-socket))
          read-sock (future (read-socket out from-socket))]
      @read-stdin
      (debug "shutting down ...")
      (Thread/sleep 1000)
      (close! sock)
      (shutdown-agents))))
