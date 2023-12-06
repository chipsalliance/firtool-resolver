#!/usr/bin/env bash

set -ex

THIS_DIR=$(cd "$(dirname "$0")"; pwd -P)

OS=$1

export COURSIER_CACHE="$PWD/coursier_cache"
rm -rf $COURSIER_CACHE
export FIRTOOL_RESOLVER_VERSION=$(./mill show firtool-resolver.publishVersion | xargs)
export LLVM_FIRTOOL_VERSION=$(./mill show llvm-firtool.publishVersion | xargs)
# If there's a -SNAPSHOT in LLVM_FIRTOOL_VERSION, strip it
export FIRTOOL_VERSION=${LLVM_FIRTOOL_VERSION%-SNAPSHOT}

$THIS_DIR/negative.sh 2>&1 | FileCheck $THIS_DIR/negative.sh

# TODO remove this line, use maven central published versions
./mill llvm-firtool.publishLocal

./mill firtool-resolver.publishLocal

$THIS_DIR/on_classpath.sh 2>&1 | FileCheck -DLLVM_FIRTOOL_VERSION="$LLVM_FIRTOOL_VERSION" -DFIRTOOL_VERSION="$FIRTOOL_VERSION" $THIS_DIR/on_classpath.sh

$THIS_DIR/fetched.sh 2>&1 | FileCheck -DLLVM_FIRTOOL_VERSION="$LLVM_FIRTOOL_VERSION" -DFIRTOOL_VERSION="$FIRTOOL_VERSION" $THIS_DIR/fetched.sh

$THIS_DIR/firtool_path.sh 2>&1 | FileCheck -DLLVM_FIRTOOL_VERSION="$LLVM_FIRTOOL_VERSION" -DFIRTOOL_VERSION="$FIRTOOL_VERSION" $THIS_DIR/firtool_path.sh

cache="$PWD/firtool_cache"
if [[ "$OS" == "windows" ]]; then
  # The windows path separator causes problems, just use local path on Windows
  filecheck_cache=$(basename $cache)
else
  filecheck_cache=$cache
fi
rm -rf $cache
FIRTOOL_CACHE=$cache $THIS_DIR/firtool_cache.sh 2>&1 | FileCheck -DLLVM_FIRTOOL_VERSION="$LLVM_FIRTOOL_VERSION" -DFIRTOOL_VERSION="$FIRTOOL_VERSION" -DFIRTOOL_CACHE="$filecheck_cache" $THIS_DIR/firtool_cache.sh
