#!/usr/bin/env bash

set -euo pipefail

THIS_DIR=$(cd "$(dirname "$0")"; pwd -P)
MIRROR=$(mktemp -d)
trap 'rm -rf "$MIRROR"' EXIT
if command -v cygpath >/dev/null 2>&1; then
  MIRROR=$(cygpath -m "$MIRROR")
fi
export MIRROR
# Package an actual firtool binary from the earlier GitHub test in a small local mirror.
FIRTOOL=$(cs launch --scala "$SCALA_VERSION" \
  org.chipsalliance::firtool-resolver:$FIRTOOL_RESOLVER_VERSION \
  --main firtoolresolver.Main -- "$FIRTOOL_VERSION")
export FIRTOOL
URL=$(python3 "$THIS_DIR/mirror.py")
if CHISEL_FIRTOOL_URL="$URL" cs launch --scala "$SCALA_VERSION" \
  org.chipsalliance::firtool-resolver:$FIRTOOL_RESOLVER_VERSION \
  --main firtoolresolver.Main -- -v checksum-test; then
  exit 1
fi
# CHECK: Downloading firtool checksum-test for
# CHECK: SHA-256 checksum mismatch for {{.*}}: expected {{(0{64})}}, actual {{([0-9a-f]{64})}}
# CHECK: Attempting to fetch org.chipsalliance:llvm-firtool:checksum-test
# CHECK: Failed to fetch firtool:
# CHECK: CHISEL_FIRTOOL_PATH not set
# CHECK: firtool version not found in resources
# CHECK: Firtool binary with version (checksum-test) does not already exist
# CHECK: SHA-256 checksum mismatch for {{.*}}: expected {{(0{64})}}, actual {{([0-9a-f]{64})}}

# A failed download must leave neither the binary nor a partial archive behind.
test ! -e "$CHISEL_FIRTOOL_CACHE/checksum-test/bin/firtool"
test -z "$(compgen -G "$CHISEL_FIRTOOL_CACHE/checksum-test/firtool-archive-*" || true)"
