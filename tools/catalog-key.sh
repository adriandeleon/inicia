#!/usr/bin/env bash
# Makes the key pair that signs the catalog on inicia's site (once, or to replace a lost key):
#   tools/catalog-key.sh
# The private key goes straight into the repository's CATALOG_SIGNING_KEY secret (through gh) and
# is never written anywhere else; the public key goes into
# src/main/resources/dev/inicia/catalog-key.pub, to commit, so the next inicia.exe trusts it.
# A replaced key only reaches inicia.exe builds made after it: older ones keep to their built-in
# catalogs until they're downloaded again.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
pub="$root/src/main/resources/dev/inicia/catalog-key.pub"

key=$(openssl genpkey -algorithm ed25519)
gh secret set CATALOG_SIGNING_KEY --repo adriandeleon/inicia <<< "$key"
openssl pkey -pubout -outform DER <<< "$key" | base64 -w0 > "$pub"
echo >> "$pub"
unset key
echo "CATALOG_SIGNING_KEY is set on GitHub; the public key is in ${pub#"$root"/}: commit it."
