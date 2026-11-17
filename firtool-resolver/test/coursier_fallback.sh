#!/usr/bin/env bash

set -euo pipefail

FIRTOOL=$(
COURSIER_REPOSITORIES=central CHISEL_FIRTOOL_URL=file:///nonexistent cs launch --scala "$SCALA_VERSION" \
  --repository ivy2local \
  org.chipsalliance::firtool-resolver:$FIRTOOL_RESOLVER_VERSION \
  --main firtoolresolver.Main -- -v "$FIRTOOL_VERSION"
)
# CHECK: Checking CHISEL_FIRTOOL_PATH for firtool
# CHECK: CHISEL_FIRTOOL_PATH not set
# CHECK: Checking resources for firtool
# CHECK: firtool version not found in resources
# CHECK: Firtool binary with version ([[FIRTOOL_VERSION]]) does not already exist
# CHECK: Attempting to fetch firtool [[FIRTOOL_VERSION]] from GitHub releases
# CHECK: Fetching firtool [[FIRTOOL_VERSION]] from GitHub failed:
# CHECK: Attempting to fetch org.chipsalliance:llvm-firtool:[[FIRTOOL_VERSION]]
# CHECK: Successfully fetched
# CHECK: Loading
# CHECK: to search its resources
# CHECK: Checking resources for firtool
# CHECK: Firtool version [[FIRTOOL_VERSION]] found in resources
"$FIRTOOL" --version
# CHECK: CIRCT firtool-[[FIRTOOL_VERSION]]
