#!/usr/bin/env bash
# Negative test that we are actually using the locally published version
# Exclude local Ivy artifacts so this also works on repeated development runs.

COURSIER_REPOSITORIES=central cs launch --scala $SCALA_VERSION org.chipsalliance::firtool-resolver:$FIRTOOL_RESOLVER_VERSION --main firtoolresolver.Main
# CHECK: Can't find a scala version suffix for org.chipsalliance::firtool-resolver:{{.+}}
