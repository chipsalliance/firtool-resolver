#!/usr/bin/env bash

FIRTOOL=$(
cs launch --scala 2.13.11 \
  org.chipsalliance::firtool-resolver:$FIRTOOL_RESOLVER_VERSION \
  org.chipsalliance:llvm-firtool:$LLVM_FIRTOOL_VERSION \
  --main firtoolresolver.Main \
  -- \
  -v \
  $LLVM_FIRTOOL_VERSION
)
# CHECK: Checking FIRTOOL_PATH for firtool
# CHECK: FIRTOOL_PATH not set
# CHECK: Checking resources for firtool
# CHECK: Firtool version [[LLVM_FIRTOOL_VERSION]] found in resources
# CHECK: Copying firtool from resources to{{.*}}[[FIRTOOL_CACHE]]{{.}}[[LLVM_FIRTOOL_VERSION]]{{.}}bin{{.}}firtool
$FIRTOOL --version
# CHECK: CIRCT firtool-[[FIRTOOL_VERSION]]
