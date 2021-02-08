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

(def running
  (atom true))

(def pipe-state
  (volatile! nil))

(defn handle-pipe! []
  (Signal/handle
    (Signal. "PIPE")
    (reify SignalHandler
      (handle [_ _]
        (debug ":PIPE")
        (vreset! pipe-state :PIPE)))))

(defn -main [& args]
  (handle-pipe!)
  (debug "starting proxy ...")
  (let [in (BufferedInputStream. System/in)
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

    (let [read-stdin (future
                       (debug "reading from System/in ...")
                       (try
                         (let [buf (byte-array 1024)]
                           (while @running
                             (let [num-bytes (.read in buf)]
                               (if (not= -1 num-bytes)
                                 (do
                                   (.write to-socket buf 0 num-bytes)
                                   (.flush to-socket))
                                 (do
                                   (debug "System/in closed!")
                                   (reset! running false)
                                   (close! to-socket))))))
                         (catch Exception e
                           (debug (str "error during reading System/in: " (ex-message e))))))

          read-sock (future
                      (debug "reading from socket ...")
                      (try
                        (let [buf (byte-array 1024)]
                          (while @running
                            (let [num-bytes (.read from-socket buf)]
                              (if (not= -1 num-bytes)
                                (do
                                  (.write out buf 0 num-bytes)
                                  (.flush out))
                                (do
                                  (debug "reading from socket closed!")
                                  (reset! running false)
                                  (close! in))))))
                        (catch Exception e
                          (debug (str "error during reading from socket: " (ex-message e))))))]
      (shutdown-agents))))
