#!/usr/bin/env bash

FIRTOOL=$(
cs launch --scala 2.13.11 \
  org.chipsalliance::firtool-resolver:$VERSION \
  org.chipsalliance:llvm-firtool:1.48.0-SNAPSHOT \
  --main firtoolresolver.Main \
  -- \
  -v \
  1.48.0
)
# CHECK: Checking FIRTOOL_PATH for firtool
# CHECK: FIRTOOL_PATH not set
# CHECK: Checking resources for firtool
# CHECK: Firtool version 1.48.0 found in resources
# CHECK: Copying firtool from resources to [[FIRTOOL_CACHE]]/1.48.0/bin/firtool
$FIRTOOL --version
# CHECK: CIRCT firtool-1.48.0
