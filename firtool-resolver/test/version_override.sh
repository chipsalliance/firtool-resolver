#!/usr/bin/env bash

set -euo pipefail

# The earlier GitHub test has populated this version's cache.
echo 'Environment override'
# CHECK: Environment override
FIRTOOL=$(
CHISEL_FIRTOOL_VERSION="$FIRTOOL_VERSION" cs launch --scala "$SCALA_VERSION" \
  org.chipsalliance::firtool-resolver:$FIRTOOL_RESOLVER_VERSION \
  --main firtoolresolver.Main -- -v 0.0.0
)
# CHECK: Using explicit firtool version override: [[FIRTOOL_VERSION]]
# CHECK-NOT: Checking resources for firtool
# CHECK: Firtool binary with version ([[FIRTOOL_VERSION]]) {{.*}} already exists
"$FIRTOOL" --version
# CHECK: CIRCT firtool-[[FIRTOOL_VERSION]]

echo 'Property override wins over environment and classpath'
# CHECK: Property override wins over environment and classpath
FIRTOOL=$(
CHISEL_FIRTOOL_VERSION=0.0.0 cs launch --scala "$SCALA_VERSION" \
  --java-opt "-Dchisel.firtool.version=$FIRTOOL_VERSION" \
  org.chipsalliance::firtool-resolver:$FIRTOOL_RESOLVER_VERSION \
  org.chipsalliance:llvm-firtool:$LLVM_FIRTOOL_VERSION \
  --main firtoolresolver.Main -- -v 0.0.1
)
# CHECK: Using explicit firtool version override: [[FIRTOOL_VERSION]]
# CHECK: Firtool version override [[FIRTOOL_VERSION]] takes precedence over llvm-firtool [[LLVM_FIRTOOL_VERSION]] on the classpath
# CHECK-NOT: Checking resources for firtool
# CHECK: Firtool binary with version ([[FIRTOOL_VERSION]]) {{.*}} already exists
"$FIRTOOL" --version
# CHECK: CIRCT firtool-[[FIRTOOL_VERSION]]

echo 'Invalid override'
# CHECK: Invalid override
if CHISEL_FIRTOOL_VERSION=../escape cs launch --scala "$SCALA_VERSION" \
  org.chipsalliance::firtool-resolver:$FIRTOOL_RESOLVER_VERSION \
  --main firtoolresolver.Main -- -v "$FIRTOOL_VERSION"; then
  exit 1
fi
# CHECK: Invalid firtool version: ../escape
# CHECK-NOT: Attempting to fetch
