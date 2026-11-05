#!/usr/bin/env bash

set -euxo pipefail

THIS_DIR=$(cd "$(dirname "$0")"; pwd -P)

OS=$1
export SCALA_VERSION=$2

# Isolate each run, including downloads, from the user's existing caches and settings.
TEST_DIR=$(mktemp -d)
if [[ "$OS" == windows ]]; then
  TEST_DIR=$(cygpath -m "$TEST_DIR")
fi
trap 'rm -rf "$TEST_DIR"' EXIT
unset CHISEL_FIRTOOL_PATH CHISEL_FIRTOOL_VERSION CHISEL_FIRTOOL_URL
export COURSIER_CACHE="$TEST_DIR/coursier"
export CHISEL_FIRTOOL_CACHE="$TEST_DIR/firtool"
# Mill records dependency paths in its build cache. Keep its dependencies in
# the persistent default cache while isolating the resolver's network fetches.
mill_run() { env -u COURSIER_CACHE ./mill -i "$@"; }
FIRTOOL_RESOLVER_VERSION=$(mill_run show firtool-resolver[$SCALA_VERSION].publishVersion | xargs)
LLVM_FIRTOOL_ARTIFACT_VERSION=$(mill_run show llvm-firtool.publishVersion | xargs)
export FIRTOOL_RESOLVER_VERSION
# GitHub releases use the unsuffixed version.
export FIRTOOL_VERSION=${LLVM_FIRTOOL_ARTIFACT_VERSION%-SNAPSHOT}

# Expected failures are checked by FileCheck, rather than by the pipeline status.
{ bash "$THIS_DIR/negative.sh" 2>&1 || true; } | FileCheck "$THIS_DIR/negative.sh"

# Retain local publication for the legacy classpath tests. The Coursier fallback
# uses the unsuffixed release version, which is already on Maven Central.
mill_run llvm-firtool.publishLocal

mill_run firtool-resolver[$SCALA_VERSION].publishLocal

mill_run firtool-resolver[$SCALA_VERSION].test.run

# The build needs the unsuffixed version; legacy launches need the published one.
export LLVM_FIRTOOL_VERSION=$LLVM_FIRTOOL_ARTIFACT_VERSION

# Sanity check: the firtool-resolver jar must not bundle scala-library or other scala jars
RESOLVER_JAR="out/firtool-resolver/$SCALA_VERSION/jar.dest/out.jar"
if [[ ! -f "$RESOLVER_JAR" ]]; then
  echo "ERROR: firtool-resolver jar not found at $RESOLVER_JAR"
  exit 1
fi
if jar tf "$RESOLVER_JAR" | grep -q "^scala"; then
  echo "ERROR: firtool-resolver jar contains scala entries that should not be bundled:"
  jar tf "$RESOLVER_JAR" | grep "^scala" | head -10
  exit 1
fi
# Also sanity check that we're checking the correct jar
if ! jar tf "$RESOLVER_JAR" | grep -q "^firtoolresolver/"; then
  echo "ERROR: firtool-resolver jar is missing firtoolresolver/ classes"
  exit 1
fi

bash "$THIS_DIR/on_classpath.sh" 2>&1 | FileCheck -DLLVM_FIRTOOL_VERSION="$LLVM_FIRTOOL_VERSION" -DFIRTOOL_VERSION="$FIRTOOL_VERSION" "$THIS_DIR/on_classpath.sh"

bash "$THIS_DIR/installed.sh" 2>&1 | FileCheck -DLLVM_FIRTOOL_VERSION="$LLVM_FIRTOOL_VERSION" -DFIRTOOL_VERSION="$FIRTOOL_VERSION" "$THIS_DIR/installed.sh"

bash "$THIS_DIR/github.sh" 2>&1 | FileCheck -DFIRTOOL_VERSION="$FIRTOOL_VERSION" "$THIS_DIR/github.sh"

bash "$THIS_DIR/version_override.sh" 2>&1 | FileCheck -DLLVM_FIRTOOL_VERSION="$LLVM_FIRTOOL_VERSION" -DFIRTOOL_VERSION="$FIRTOOL_VERSION" "$THIS_DIR/version_override.sh"

bash "$THIS_DIR/checksum_mismatch.sh" 2>&1 | FileCheck "$THIS_DIR/checksum_mismatch.sh"

if [[ "$OS" == macos && $(uname -m) == arm64 ]]; then
  bash "$THIS_DIR/platform_fallback.sh" 2>&1 | FileCheck "$THIS_DIR/platform_fallback.sh"
fi

CHISEL_FIRTOOL_CACHE="$TEST_DIR/coursier-fallback" bash "$THIS_DIR/coursier_fallback.sh" 2>&1 | FileCheck -DFIRTOOL_VERSION="$FIRTOOL_VERSION" "$THIS_DIR/coursier_fallback.sh"

{ bash "$THIS_DIR/firtool_path.sh" 2>&1 || true; } | FileCheck -DLLVM_FIRTOOL_VERSION="$LLVM_FIRTOOL_VERSION" -DFIRTOOL_VERSION="$FIRTOOL_VERSION" "$THIS_DIR/firtool_path.sh"

cache="$TEST_DIR/firtool_cache"
if [[ "$OS" == "windows" ]]; then
  # The windows path separator causes problems, just use local path on Windows
  filecheck_cache=$(basename $cache)
else
  filecheck_cache=$cache
fi
CHISEL_FIRTOOL_CACHE="$cache" bash "$THIS_DIR/firtool_cache.sh" 2>&1 | FileCheck -DLLVM_FIRTOOL_VERSION="$LLVM_FIRTOOL_VERSION" -DFIRTOOL_VERSION="$FIRTOOL_VERSION" -DCHISEL_FIRTOOL_CACHE="$filecheck_cache" "$THIS_DIR/firtool_cache.sh"
