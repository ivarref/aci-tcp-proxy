#!/bin/bash

rm ./proxy

set -e

clojure -M:native-image

clojure -Sdeps '{:deps {aleph/aleph {:mvn/version "0.4.6"}}}' \
        -M --report stderr \
        -e "(require '[aleph.tcp :as tcp]) \
            (require '[manifold.stream :as s]) \
            (import '(java.net InetSocketAddress)) \
            (println \"starting server...\") \
            (tcp/start-server (fn [s info] (s/connect s s)) {:port 7777 :socket-address (InetSocketAddress. \"127.0.0.1\" 7777)}) \
            #_(shutdown-agents)" \
            & echo $! > ./.echo-server.pid

echo "waiting for echo server..."

while ! nc -z 127.0.0.1 7777; do
  sleep 0.1
done

echo "echo server ready!"

./proxy < proxy > out.bin

echo "proxy done!"

kill $(cat ./.echo-server.pid) || true

diff -qs proxy out.bin

cp -fv ./proxy resources/ivarref/aci-tcp-proxy/proxy
