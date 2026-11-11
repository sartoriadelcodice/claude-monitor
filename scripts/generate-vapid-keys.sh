#!/usr/bin/env bash
# Genera una coppia di chiavi VAPID (base64url) da copiare nel file .env.
set -euo pipefail

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

key="$(openssl ecparam -name prime256v1 -genkey -noout 2>/dev/null)"
# Chiave pubblica: gli ultimi 65 byte del DER (punto non compresso)
public="$(printf '%s\n' "$key" | openssl ec -pubout -outform DER 2>/dev/null | tail -c 65 | b64url)"
# Chiave privata: i 32 byte dello scalare
private="$(printf '%s\n' "$key" | openssl ec -outform DER 2>/dev/null | dd bs=1 skip=7 count=32 2>/dev/null | b64url)"

echo "VAPID_PUBLIC_KEY=${public}"
echo "VAPID_PRIVATE_KEY=${private}"
