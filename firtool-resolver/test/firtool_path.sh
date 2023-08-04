#!/usr/bin/env bash

# From a previous test
FIRTOOL_BIN=$(
cs launch --scala 2.13.11 \
  org.chipsalliance::firtool-resolver:$VERSION \
  org.chipsalliance:llvm-firtool:1.48.0-SNAPSHOT \
  --main firtoolresolver.Main \
  -- \
  1.48.0
)
export FIRTOOL_PATH=$(dirname $FIRTOOL_BIN)

FIRTOOL=$(
cs launch --scala 2.13.11 \
  org.chipsalliance::firtool-resolver:$VERSION \
  --main firtoolresolver.Main \
  -- \
  -v \
  1.48.0
)
# CHECK: Checking FIRTOOL_PATH for firtool
# CHECK: Running: {{.+}}/bin/firtool --version
# CHECK-NOT: FIRTOOL_PATH not set
# CHECK-NOT: Checking resources for firtool
$FIRTOOL --version
# CHECK: CIRCT firtool-1.48.0
