#!/usr/bin/env bash

FIRTOOL=$(
cs launch --scala $SCALA_VERSION \
  org.chipsalliance::firtool-resolver:$FIRTOOL_RESOLVER_VERSION \
  --main firtoolresolver.Main \
  -- \
  -v \
  $LLVM_FIRTOOL_VERSION
)
# CHECK: Checking CHISEL_FIRTOOL_PATH for firtool
# CHECK: CHISEL_FIRTOOL_PATH not set
# CHECK: Checking resources for firtool
# CHECK: firtool version not found in resources
# CHECK: Firtool binary with default version ([[LLVM_FIRTOOL_VERSION]]) does not already exist
# CHECK: Attempting to fetch org.chipsalliance:llvm-firtool:[[LLVM_FIRTOOL_VERSION]]
# CHECK: Successfully fetched
# CHECK: Loading
# CHECK: to search its resources
# CHECK: Checking resources for firtool
# CHECK: Firtool version [[LLVM_FIRTOOL_VERSION]] found in resources
$FIRTOOL --version
# CHECK: CIRCT firtool-[[FIRTOOL_VERSION]]
