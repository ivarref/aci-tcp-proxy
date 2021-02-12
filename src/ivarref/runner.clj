(ns ivarref.runner
  (:require [clojure.tools.logging :as log]
            [babashka.process :refer [$ check]])
  (:import (java.io OutputStreamWriter InputStreamReader BufferedReader BufferedWriter)
           (java.nio.charset StandardCharsets)))

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
          (log/info lin))
        (log/debug "proxy stderr exhausted"))
      (log/info "waiting for java app to emit a single line... :-)")
      (.readLine stdout)
      (let [spent-time (- (System/currentTimeMillis) start-time)]
        (log/info "ready in" spent-time "ms"))
      (future
        (doseq [lin (line-seq stdout)]
          (consume-stdout lin))
        (log/debug "proxy stdout exhausted"))
      {:in in})))

(comment
  (let [{:keys [in]} (launch-java-file
                       "src/Hello.java"
                       {:consume-stdout (fn [lin] (log/info "got stdout:" lin))})]
    (.write in "Hello From Clojure\n")
    (Thread/sleep 1000)
    (.close in)))