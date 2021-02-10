#!/bin/bash

clojure -Sdeps '{:deps {aleph/aleph {:mvn/version "0.4.6"}}}' \
        -J-Dclojure.main.report=stderr \
        -M \
        -e "(require '[aleph.tcp :as tcp]) \
            (require '[manifold.stream :as s]) \
            (import '(java.net InetSocketAddress)) \
            (println \"starting server...\") \
            (tcp/start-server (fn [s info] (s/connect s s)) {:socket-address (InetSocketAddress. \"127.0.0.1\" 7777)}) \
            #_(shutdown-agents)" \
            & echo $! > ./.echo-server.pid

echo "waiting for echo server..."

while ! nc -z 127.0.0.1 7777; do
  sleep 0.1
done

echo "echo server ready!"

echo "#!/usr/bin/java --source 11" > Proxy
cat src/Proxy.java >> Proxy
chmod +x ./Proxy

PAYLOAD=$(printf "hello\ndear\nworld!" | base64)
printf "localhost\n7777\n$PAYLOAD" | ./Proxy

#cp -fv Proxy $HOME/code/learn/ire-test/src/.

kill $(cat ./.echo-server.pid) || true
rm ./.echo-server.pid