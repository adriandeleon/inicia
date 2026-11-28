#!/usr/bin/env bash
# Signs the catalog the site serves, so inicia.exe can take it in place of its built-in one.
#   CATALOG_SIGNING_KEY=<PEM private key> tools/sign-catalog.sh target/web/catalog.json
# writes target/web/catalog.json.sig (the format is in SignedCatalog.java). It checks the key
# against the public key built into inicia (src/main/resources/dev/inicia/catalog-key.pub) and
# the signature against that public key, so a wrong key fails here, not in everyone's inicia.
# Without CATALOG_SIGNING_KEY it signs nothing, and inicia keeps to its built-in catalog.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
catalog="$1"
pub="$root/src/main/resources/dev/inicia/catalog-key.pub"

if [[ -z "${CATALOG_SIGNING_KEY:-}" ]]; then
  echo "::warning::CATALOG_SIGNING_KEY isn't set: the catalog isn't signed, and inicia.exe keeps to its built-in one"
  exit 0
fi
if [[ ! -f "$pub" ]]; then
  echo "no public key at $pub: run tools/catalog-key.sh" >&2
  exit 1
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
umask 077
printf '%s\n' "$CATALOG_SIGNING_KEY" > "$tmp/key.pem"

if [[ "$(openssl pkey -in "$tmp/key.pem" -pubout -outform DER | base64 -w0)" != "$(tr -d '[:space:]' < "$pub")" ]]; then
  echo "CATALOG_SIGNING_KEY isn't the key whose public half is in catalog-key.pub" >&2
  exit 1
fi

published=$(date -u +%Y-%m-%dT%H:%M:%SZ)
{ printf 'inicia-catalog 1\n%s\n' "$published"; cat "$catalog"; } > "$tmp/message"
openssl pkeyutl -sign -inkey "$tmp/key.pem" -rawin -in "$tmp/message" -out "$tmp/sig"

base64 -d <<< "$(tr -d '[:space:]' < "$pub")" > "$tmp/pub.der"
openssl pkeyutl -verify -pubin -keyform DER -inkey "$tmp/pub.der" -rawin -in "$tmp/message" -sigfile "$tmp/sig" >/dev/null

printf 'inicia-catalog-signature 1\npublished %s\ned25519 %s\n' "$published" "$(base64 -w0 "$tmp/sig")" > "$catalog.sig"
echo "signed $(basename "$catalog") (published $published)"
