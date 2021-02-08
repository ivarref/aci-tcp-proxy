#!/bin/bash

set -ex

clojure -M:native-image

./proxy < proxy > out.bin

diff proxy out.bin