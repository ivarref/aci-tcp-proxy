(ns core
  (:gen-class)
  (:import (java.io OutputStream InputStream BufferedInputStream BufferedOutputStream Closeable)
           (java.net Socket)
           (sun.misc Signal SignalHandler)))

(defn ^Socket socket [^String host ^long port]
  (Socket. host port))

(defn debug [^String s]
  (binding [*out* *err*]
    (println s)))

(defn -main [& args]
  (debug "starting proxy ...")
  (let [running (atom true)
        pipe-state (volatile! nil)
        _pipe-signal-handler (Signal/handle
                               (Signal. "PIPE")
                               (reify SignalHandler
                                 (handle [_ _]
                                   (vreset! pipe-state :PIPE))))
        in (BufferedInputStream. System/in)
        out (BufferedOutputStream. System/out)
        sock (socket "127.0.0.1" 7777)
        to-socket  (-> sock
                       ^OutputStream (.getOutputStream)
                       (BufferedOutputStream.))
        from-socket (-> sock
                        ^InputStream (.getInputStream)
                        (BufferedInputStream.))
        close! (fn [^Closeable x]
                 (when x
                   (try
                     (.close x)
                     (catch Exception e
                       nil))))]
    (debug "starting proxy ... OK")

    (future
      (debug "reading from System/in ...")
      (let [buf (byte-array 1024)]
        (while @running
          (let [num-bytes (.read in buf)]
            ;(debug (str "got " num-bytes " bytes from stdin"))
            (if (not= -1 num-bytes)
              (do
                (.write to-socket buf 0 num-bytes)
                (.flush to-socket))
              (do
                (debug "System/in closed!")
                (reset! running false)
                (close! sock)
                (close! from-socket)))))))

    (future
      (debug "reading from socket ...")
      (let [buf (byte-array 1024)]
        (while @running
          (let [num-bytes (.read from-socket buf)]
            ;(debug (str "got " num-bytes " bytes from socket"))
            (if (not= -1 num-bytes)
              (do
                (.write System/out buf 0 num-bytes)
                (.flush System/out))
              (do
                (debug "reading from socket closed!")
                (reset! running false)
                (close! in)))))))))
