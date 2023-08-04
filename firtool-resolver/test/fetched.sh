#!/usr/bin/env bash

FIRTOOL=$(
cs launch --scala 2.13.11 \
  org.chipsalliance::firtool-resolver:$VERSION \
  --main firtoolresolver.Main \
  -- \
  -v \
  1.48.0-SNAPSHOT
)
# CHECK: Checking FIRTOOL_PATH for firtool
# CHECK: FIRTOOL_PATH not set
# CHECK: Checking resources for firtool
# CHECK: firtool version not found in resources
# CHECK: Attempting to fetch org.chipsalliance:llvm-firtool:1.48.0
# CHECK: Successfully fetched
# CHECK: Loading {{.*}} to search its resources
# CHECK: Checking resources for firtool
# CHECK: Firtool version 1.48.0 found in resources
$FIRTOOL --version
# CHECK: CIRCT firtool-1.48.0
