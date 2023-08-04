#!/usr/bin/env bash

set -ex

THIS_FILE="$(readlink -f ${BASH_SOURCE[0]})"
THIS_DIR="$(dirname ${THIS_FILE})"

export COURSIER_CACHE="$PWD/coursier_cache"
rm -rf $COURSIER_CACHE
export VERSION=$(mill show firtool-resolver.publishVersion | xargs)

$THIS_DIR/negative.sh 2>&1 | FileCheck $THIS_DIR/negative.sh

# TODO remove this line, use maven central published versions
mill llvm-firtool.publishLocal

mill firtool-resolver.publishLocal

$THIS_DIR/on_classpath.sh 2>&1 | FileCheck $THIS_DIR/on_classpath.sh

$THIS_DIR/fetched.sh 2>&1 | FileCheck $THIS_DIR/fetched.sh

$THIS_DIR/firtool_path.sh 2>&1 | FileCheck $THIS_DIR/firtool_path.sh

cache="$PWD/firtool_cache"
rm -rf $cache
FIRTOOL_CACHE=$cache $THIS_DIR/firtool_cache.sh 2>&1 | FileCheck -DFIRTOOL_CACHE=$cache $THIS_DIR/firtool_cache.sh
