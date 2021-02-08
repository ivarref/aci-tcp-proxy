#!/bin/bash

rm -v ./proxy

set -ex

clojure -M:native-image

./proxy < proxy > out.bin

diff proxy out.bin