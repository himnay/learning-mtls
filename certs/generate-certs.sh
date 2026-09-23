#!/usr/bin/env bash
#
# Generates a private demo PKI for the mTLS services:
#
#   ca.crt / ca.key                 self-signed root CA (trust anchor) — stays in certs/out, never committed
#   truststore.p12                  PKCS12 truststore containing only ca.crt
#   service-consumer-keystore.p12   service-consumer identity (serverAuth + clientAuth)
#   service-producer-keystore.p12   service-producer identity (serverAuth + clientAuth)
#   service-unknown-keystore.p12    CA-signed but NOT allow-listed by service-producer (negative test)
#
# The stores are then copied onto each module's classpath:
#
#   service-producer/src/main/resources/ssl/   service-producer-keystore.p12, truststore.p12
#   service-producer/src/test/resources/ssl/   service-consumer-keystore.p12, service-unknown-keystore.p12 (test clients)
#   service-consumer/src/main/resources/ssl/   service-consumer-keystore.p12, truststore.p12
#
# Usage: ./certs/generate-certs.sh            (password defaults to "changeit")
#        STORE_PASSWORD=secret ./certs/generate-certs.sh
#
# Demo only. In production, issue certificates from your organisation's PKI
# (Vault PKI, cert-manager, AWS Private CA, ...) and never commit key material.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${OUT_DIR:-${SCRIPT_DIR}/out}"
PASSWORD="${STORE_PASSWORD:-changeit}"
CA_DAYS=3650
LEAF_DAYS=825

command -v openssl >/dev/null || { echo "openssl not found" >&2; exit 1; }
command -v keytool >/dev/null || { echo "keytool not found (install a JDK)" >&2; exit 1; }

rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}"
cd "${OUT_DIR}"

echo ">> Root CA"
openssl req -x509 -newkey rsa:4096 -sha256 -days "${CA_DAYS}" -nodes \
  -keyout ca.key -out ca.crt \
  -subj "/CN=mTLS Demo Root CA/O=com.org" \
  -addext "basicConstraints=critical,CA:TRUE" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null

issue() {
  local name="$1"
  echo ">> Leaf certificate: ${name}"

  cat > "${name}.ext" <<EOF
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth,clientAuth
subjectAltName=DNS:${name},DNS:localhost,IP:127.0.0.1
EOF

  openssl req -newkey rsa:2048 -nodes -sha256 \
    -keyout "${name}.key" -out "${name}.csr" \
    -subj "/CN=${name}/O=com.org" 2>/dev/null

  openssl x509 -req -sha256 -days "${LEAF_DAYS}" \
    -in "${name}.csr" -CA ca.crt -CAkey ca.key -CAcreateserial \
    -extfile "${name}.ext" -out "${name}.crt" 2>/dev/null

  openssl pkcs12 -export -name "${name}" \
    -inkey "${name}.key" -in "${name}.crt" -certfile ca.crt \
    -out "${name}-keystore.p12" -passout "pass:${PASSWORD}"

  rm -f "${name}.csr" "${name}.ext"
}

issue service-consumer
issue service-producer
issue service-unknown

echo ">> Truststore"
keytool -importcert -noprompt -alias mtls-demo-ca -file ca.crt \
  -keystore truststore.p12 -storetype PKCS12 -storepass "${PASSWORD}" >/dev/null

rm -f ca.srl

echo ">> Copying stores into module resources"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
PRODUCER_MAIN="${ROOT_DIR}/service-producer/src/main/resources/ssl"
PRODUCER_TEST="${ROOT_DIR}/service-producer/src/test/resources/ssl"
CONSUMER_MAIN="${ROOT_DIR}/service-consumer/src/main/resources/ssl"
mkdir -p "${PRODUCER_MAIN}" "${PRODUCER_TEST}" "${CONSUMER_MAIN}"

cp service-producer-keystore.p12 truststore.p12 "${PRODUCER_MAIN}/"
cp service-consumer-keystore.p12 service-unknown-keystore.p12 "${PRODUCER_TEST}/"
cp service-consumer-keystore.p12 truststore.p12 "${CONSUMER_MAIN}/"

echo "Done. CA + PEM material in ${OUT_DIR}; PKCS12 stores copied to module resources."
