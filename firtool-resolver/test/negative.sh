#!/usr/bin/env bash
# Negative test that we are actually using the locally published version
# This test needs to be run before publishLocal
# If this test fails when run locally, you may need to wipe your local ivy cache

cs launch --scala 2.13.11 org.chipsalliance::firtool-resolver:$FIRTOOL_RESOLVER_VERSION --main firtoolresolver.Main
# CHECK: Resolution error: Error downloading org.chipsalliance:firtool-resolver_2.13:{{.+}}
