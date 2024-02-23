#!/usr/bin/env bash

# From a previous test
FIRTOOL_BIN=$(
cs launch --scala $SCALA_VERSION \
  org.chipsalliance::firtool-resolver:$FIRTOOL_RESOLVER_VERSION \
  org.chipsalliance:llvm-firtool:$LLVM_FIRTOOL_VERSION \
  --main firtoolresolver.Main \
  -- \
  $LLVM_FIRTOOL_VERSION
)
export CHISEL_FIRTOOL_PATH=$(dirname $FIRTOOL_BIN)

FIRTOOL=$(
cs launch --scala $SCALA_VERSION \
  org.chipsalliance::firtool-resolver:$FIRTOOL_RESOLVER_VERSION \
  --main firtoolresolver.Main \
  -- \
  -v \
  $LLVM_FIRTOOL_VERSION
)
# CHECK: Checking CHISEL_FIRTOOL_PATH for firtool
# CHECK: Running: {{.+}}bin{{.}}firtool --version
# CHECK-NOT: CHISEL_FIRTOOL_PATH not set
# CHECK-NOT: Checking resources for firtool
$FIRTOOL --version
# CHECK: CIRCT firtool-[[FIRTOOL_VERSION]]

# We need to also check that if CHISEL_FIRTOOL_PATH is set, we return failure if something goes wrong
# rather than just going ahead and fetching the dfeault version
mv $FIRTOOL_BIN ${FIRTOOL_BIN}_renamed

cs launch --scala $SCALA_VERSION \
  org.chipsalliance::firtool-resolver:$FIRTOOL_RESOLVER_VERSION \
  --main firtoolresolver.Main \
  -- \
  -v \
  $LLVM_FIRTOOL_VERSION
# CHECK: Checking CHISEL_FIRTOOL_PATH for firtool
# CHECK: Running: {{.+}}bin{{.}}firtool --version
# CHECK: Cannot run program {{.+}}firtool
# CHECK-NOT: CHISEL_FIRTOOL_PATH not set
# CHECK-NOT: Checking resources for firtool
