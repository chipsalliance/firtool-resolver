#!/usr/bin/env bash
# Negative test that we are actually using the locally published version
# This test needs to be run before publishLocal
# If this test fails when run locally, you may need to wipe your local ivy cache

cs launch --scala $SCALA_VERSION org.chipsalliance::firtool-resolver:$FIRTOOL_RESOLVER_VERSION --main firtoolresolver.Main
# CHECK: Can't find a scala version suffix for org.chipsalliance::firtool-resolver:{{.+}}
