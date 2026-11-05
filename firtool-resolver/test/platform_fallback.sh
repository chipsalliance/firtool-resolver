#!/usr/bin/env bash

set -euo pipefail

FIRTOOL=$(cs launch --scala "$SCALA_VERSION" \
  org.chipsalliance::firtool-resolver:$FIRTOOL_RESOLVER_VERSION \
  --main firtoolresolver.Main -- -v 1.143.0)
# CHECK: Downloading SHA-256 checksum from {{.*}}firrtl-bin-macos-arm64.tar.gz.sha256
# CHECK: No GitHub release asset for firtool 1.143.0 on macos-aarch64
# CHECK: Falling back to platform macos-x64
# CHECK: Downloading firtool 1.143.0 for macos-x64 from
# CHECK: Verified SHA-256 checksum:
"$FIRTOOL" --version
# CHECK: CIRCT firtool-1.143.0
