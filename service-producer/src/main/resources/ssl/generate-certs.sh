#!/usr/bin/env bash
#
# Regenerates service-producer's TLS material, signed by the shared demo CA.
#
#   src/main/resources/ssl/service-producer-keystore.p12   server identity (key + cert + CA chain)
#   src/main/resources/ssl/truststore.p12                  demo root CA — validates client certs
#   src/test/resources/ssl/service-consumer-keystore.p12   test client: allow-listed CN
#   src/test/resources/ssl/service-unknown-keystore.p12    test client: trusted CA, CN NOT allow-listed
#
# The CA (ca.crt / ca.key) lives in <repo>/certs/out and is shared with service-consumer's
# own generate-certs.sh. It is created on first run; if it is (re)created here, re-run the
# consumer script so both services trust the same CA.
#
# Usage (from anywhere):
#   service-producer/src/main/resources/ssl/generate-certs.sh
#   STORE_PASSWORD=secret service-producer/src/main/resources/ssl/generate-certs.sh
#
# Excluded from the packaged jar (maven-jar-plugin). Demo only — use your organisation's PKI
# (Vault PKI, cert-manager, AWS Private CA, ...) in production and never commit ca.key.

set -euo pipefail

SSL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "${SSL_DIR}/../../../.." && pwd)"       # ssl -> resources -> main -> src -> module
ROOT_DIR="$(cd "${MODULE_DIR}/.." && pwd)"
CA_DIR="${CA_DIR:-${ROOT_DIR}/certs/out}"
TEST_SSL_DIR="${MODULE_DIR}/src/test/resources/ssl"
PASSWORD="${STORE_PASSWORD:-changeit}"
CA_DAYS=3650
LEAF_DAYS=825

command -v openssl >/dev/null || { echo "openssl not found" >&2; exit 1; }
command -v keytool >/dev/null || { echo "keytool not found (install a JDK)" >&2; exit 1; }

mkdir -p "${CA_DIR}" "${TEST_SSL_DIR}"
cd "${CA_DIR}"

if [[ ! -f ca.key || ! -f ca.crt ]]; then
  echo ">> Creating shared root CA in ${CA_DIR}"
  openssl req -x509 -newkey rsa:4096 -sha256 -days "${CA_DAYS}" -nodes \
    -keyout ca.key -out ca.crt \
    -subj "/CN=mTLS Demo Root CA/O=com.org" \
    -addext "basicConstraints=critical,CA:TRUE" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null
  echo "!! New CA — also run service-consumer/src/main/resources/ssl/generate-certs.sh"
else
  echo ">> Reusing shared root CA ${CA_DIR}/ca.crt"
fi

# issue <cn> <destination-dir>
issue() {
  local name="$1" dest="$2"
  echo ">> Leaf certificate ${name} -> ${dest}"

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
    -out "${dest}/${name}-keystore.p12" -passout "pass:${PASSWORD}"

  rm -f "${name}.csr" "${name}.ext" ca.srl
}

issue service-producer "${SSL_DIR}"
issue service-consumer "${TEST_SSL_DIR}"
issue service-unknown  "${TEST_SSL_DIR}"

echo ">> Truststore -> ${SSL_DIR}/truststore.p12"
rm -f "${SSL_DIR}/truststore.p12"
keytool -importcert -noprompt -alias mtls-demo-ca -file ca.crt \
  -keystore "${SSL_DIR}/truststore.p12" -storetype PKCS12 -storepass "${PASSWORD}" >/dev/null

echo "Done. PEM copies for curl are in ${CA_DIR}"
