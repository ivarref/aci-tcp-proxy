(ns ivarref.nrepl-proxy-demo
  (:require [aleph.tcp :as tcp]
            [clojure.tools.logging :as log]
            [manifold.stream :as s])
  (:import (java.net InetSocketAddress)))

; $ clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "0.8.3"}}}' -M -m nrepl.cmdline --port 5600 --headless

(defonce sent (atom 0))

(defonce recv (atom 0))

(defn sock-handler [s]
  (let [srv @(tcp/client {:host "127.0.0.1" :port 5600})]
    (s/consume
      (fn [byt]
        (s/put! srv byt)
        (log/info "sent" (alength byt))
        (swap! sent (partial + (alength byt))))
      s)
    (s/consume
      (fn [byt]
        (s/put! s byt)
        (log/info "received" (alength byt))
        (swap! recv (partial + (alength byt))))
      srv)))

(defonce
  proxy-server
  (tcp/start-server
    (fn [s info] (sock-handler s))
    {:socket-address (InetSocketAddress. "127.0.0.1" 5700)}))

