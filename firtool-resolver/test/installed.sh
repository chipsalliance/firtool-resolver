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
# CHECK: Firtool binary with default version ([[LLVM_FIRTOOL_VERSION]]) {{.*}}firtool already exists
$FIRTOOL --version
# CHECK: CIRCT firtool-[[FIRTOOL_VERSION]]

# Now delete the binary so it can be fetched again by later tests
rm -rf $FIRTOOL
