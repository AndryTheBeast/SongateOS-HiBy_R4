#!/bin/bash
# Make the SongateOS release signing key (run once, in WSL/Linux).
#
#   make_release_key.sh <private key folder>
#
# Creates in <private key folder> (keep it OFFLINE, never in this repo, back it up twice):
#   songateos_release.pk8        private key (PKCS#8 DER, RSA 4096) - signs the OTA zips
#   songateos_release.x509.pem   certificate (public)
# and copies the public parts into this repo:
#   ota/keys/songateos_release.x509.pem
#   ota/keys/otacerts.zip            (the certificate as the recoveries and Android read it)
#
# Lose the private key and existing installs can no longer verify new updates (they'd need a manual
# update of the recovery and the updater). Leak it and anyone can sign zips our updater accepts.
set -euo pipefail
OUT=${1:?usage: make_release_key.sh <private key folder outside the repo>}
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
case "$(cd "$(dirname "$OUT")" 2>/dev/null && pwd)/" in
  "$REPO"/*) echo "refusing: the private key folder must be outside the repo ($REPO)"; exit 1 ;;
esac
mkdir -p "$OUT"; chmod 700 "$OUT"
[ -e "$OUT/songateos_release.pk8" ] && { echo "refusing: $OUT/songateos_release.pk8 already exists"; exit 1; }
umask 077
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
# RSA 4096, e=65537 (TWRP's verifier accepts 2048/4096 bits with e=3 or 65537; Android's RecoverySystem too).
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -pkeyopt rsa_keygen_pubexp:65537 -out "$T/key.pem" 2>/dev/null
# No personal data in the certificate subject.
openssl req -new -x509 -sha256 -key "$T/key.pem" -days 10950 \
  -subj "/O=SongateOS/OU=Release/CN=SongateOS release key" -out "$OUT/songateos_release.x509.pem"
openssl pkcs8 -topk8 -inform PEM -outform DER -nocrypt -in "$T/key.pem" -out "$OUT/songateos_release.pk8"
chmod 400 "$OUT/songateos_release.pk8"
mkdir -p "$HERE/keys"
cp "$OUT/songateos_release.x509.pem" "$HERE/keys/songateos_release.x509.pem"
rm -f "$HERE/keys/otacerts.zip"
(cd "$HERE/keys" && zip -q -X otacerts.zip songateos_release.x509.pem)
echo "private key : $OUT/songateos_release.pk8 (back it up offline)"
echo "certificate : $HERE/keys/songateos_release.x509.pem"
openssl x509 -in "$OUT/songateos_release.x509.pem" -noout -subject -enddate -fingerprint -sha256
