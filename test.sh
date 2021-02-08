#!/bin/bash

rm -v ./proxy

set -ex

clojure -M:native-image

./proxy < proxy > out.bin

diff proxy out.bin

mkdir -p resources/ivarref/aci-tcp-proxy

cp -fv ./proxy resources/ivarref/aci-tcp-proxy/proxy

clojure -M:jar
