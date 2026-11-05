#!/usr/bin/env bash

set -euo pipefail

FIRTOOL=$(
cs launch --scala $SCALA_VERSION \
  org.chipsalliance::firtool-resolver:$FIRTOOL_RESOLVER_VERSION \
  --main firtoolresolver.Main \
  -- \
  -v \
   $FIRTOOL_VERSION
)
# CHECK: Checking CHISEL_FIRTOOL_PATH for firtool
# CHECK: CHISEL_FIRTOOL_PATH not set
# CHECK: Checking resources for firtool
# CHECK: firtool version not found in resources
# CHECK: Firtool binary with version ([[FIRTOOL_VERSION]]) does not already exist
# CHECK: Attempting to fetch firtool [[FIRTOOL_VERSION]] from GitHub releases
# CHECK: Acquiring firtool cache lock
# CHECK: Downloading SHA-256 checksum
# CHECK: Downloading firtool [[FIRTOOL_VERSION]] for
# CHECK: Verified SHA-256 checksum:
# CHECK: Extracting firtool from
# CHECK: Atomically moving
# CHECK-NOT: Attempting to fetch org.chipsalliance:llvm-firtool:
"$FIRTOOL" --version
# CHECK: CIRCT firtool-[[FIRTOOL_VERSION]]
