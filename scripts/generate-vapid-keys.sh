#!/usr/bin/env bash
# Generates a VAPID key pair (base64url) to copy into the .env file.
set -euo pipefail

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

key="$(openssl ecparam -name prime256v1 -genkey -noout 2>/dev/null)"
# Public key: the last 65 bytes of the DER (uncompressed point)
public="$(printf '%s\n' "$key" | openssl ec -pubout -outform DER 2>/dev/null | tail -c 65 | b64url)"
# Private key: the 32 bytes of the scalar
private="$(printf '%s\n' "$key" | openssl ec -outform DER 2>/dev/null | dd bs=1 skip=7 count=32 2>/dev/null | b64url)"

echo "VAPID_PUBLIC_KEY=${public}"
echo "VAPID_PRIVATE_KEY=${private}"
