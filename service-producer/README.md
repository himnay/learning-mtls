# <span style="color:hsl(3,80%,58%)">service-producer — mTLS REST Provider</span>

## <span style="color:hsl(141,80%,58%)">Table of contents</span>

1. 🧰 [Stack](#stack)
2. 🎯 [What this service does](#what-this-service-does)
3. ✨ [Features used](#features-used)
4. 🔐 [How mutual TLS is enforced](#how-mutual-tls-is-enforced) — concepts: [Security, TLS & encryption](../README.md#security-goals)
5. 🗄️ [Database — PostgreSQL + Flyway](#database)
6. 🔑 [Encrypted DB password — Jasypt `ENC(...)`](#encrypted-db-password)
    - 6.1 [Configuration](#jasypt-configuration)
    - 6.2 [How decryption works at startup](#how-decryption-works)
    - 6.3 [What is inside `ENC(...)`](#inside-enc)
    - 6.4 [Crypto building blocks explained](#crypto-building-blocks)
    - 6.5 [Encrypt / decrypt a value](#encrypt-decrypt-a-value)
    - 6.6 [Rotating the DB password](#rotating-the-db-password)
    - 6.7 [Troubleshooting](#jasypt-troubleshooting)
7. 🌐 [API](#api)
8. ⚙️ [Configuration reference](#configuration-reference)
9. 🚀 [Running locally](#running-locally)
10. 🧪 [Testing](#testing)
11. 📁 [Project layout](#project-layout)
12. ⚠️ [Production notes](#production-notes)

<a id="stack"></a>
## <span style="color:hsl(278,80%,58%)">1. 🧰 Stack</span>

| Component          | Version / Detail                                        |
|--------------------|---------------------------------------------------------|
| Java               | 25 (enforced by super-pom: 21+)                         |
| Spring Boot        | 4.1.0 (via `learning-mtls` → `super-pom`)               |
| Web                | Spring MVC on embedded Tomcat, HTTPS only (port `8443`) |
| TLS                | Spring Boot SSL bundles, PKCS12, TLS 1.3 / 1.2          |
| Persistence        | Spring Data JDBC + PostgreSQL 19 (beta3)                |
| Migrations         | Flyway (`flyway-database-postgresql`)                   |
| Secret encryption  | Jasypt Spring Boot 4.0.4 (`PBEWITHHMACSHA512ANDAES_256`)|
| Boilerplate        | Lombok + Java records                                   |
| Dev loop           | Spring Boot DevTools (auto-restart)                     |
| Tests              | JUnit 5, Testcontainers (PostgreSQL), RestClient        |
| Build              | Maven 3.9+                                              |

<a id="what-this-service-does"></a>
## <span style="color:hsl(56,80%,50%)">2. 🎯 What this service does</span>

`service-producer` is the **server side** of the mTLS pair. It exposes one REST endpoint that
returns a localised greeting read from PostgreSQL. It accepts a connection **only** when the
caller presents a client certificate that

1. chains to the demo root CA in its truststore (checked by Tomcat during the TLS handshake), and
2. carries an allow-listed Common Name — `service-consumer` (checked by a servlet filter).

```mermaid
sequenceDiagram
    participant C as service-consumer
    participant T as Tomcat (TLS)
    participant F as ClientCertificateFilter
    participant G as GreetingController
    participant DB as PostgreSQL

    C->>T: ClientHello
    T-->>C: ServerHello + service-producer cert + CertificateRequest
    C->>T: service-consumer cert + CertificateVerify
    Note over T: chain → truststore?<br/>no → handshake aborted
    T->>F: HTTP request + X509Certificate[]
    Note over F: CN ∈ allowed-client-cns?<br/>no → 403
    F->>G: GET /api/v1/greetings/{name}?lang=fr
    G->>DB: SELECT … FROM greeting_template WHERE language_code = 'fr'
    DB-->>G: 'Bonjour, %s !'
    G-->>C: 200 {message, language, servedBy, callerCn, timestamp}
```

<a id="features-used"></a>
## <span style="color:hsl(193,80%,58%)">3. ✨ Features used</span>

| Feature | Where | Why |
|---|---|---|
| **Spring Boot SSL bundle** (`spring.ssl.bundle.jks.service-producer`) | `application.yml` | One named bundle holds keystore + truststore + protocol options; the server references it via `server.ssl.bundle`. |
| **`server.ssl.client-auth: need`** | `application.yml` | Makes Tomcat *require* a client cert; untrusted or missing certs fail in the handshake — no HTTP request is ever produced. |
| **CN allow-list filter** | `security/ClientCertificateFilter` | Transport trust ≠ authorization. Any cert from the CA passes TLS; only listed CNs reach the controller (others get `403`). |
| **`@ConfigurationProperties` record** | `security/MtlsProperties` | Immutable, type-safe binding of `mtls.allowed-client-cns`. |
| **Spring Data JDBC with a record entity** | `greeting/GreetingTemplate`, `GreetingTemplateRepository` | Zero-boilerplate read model; no JPA/Hibernate needed for a lookup table. |
| **Flyway migrations** | `src/main/resources/db/migration` | Schema (`V1`) and seed data (`V2`) are versioned and applied on startup. |
| **Jasypt `ENC(...)`** | `spring.datasource.password` | DB password is stored encrypted in YAML; decrypted in memory at startup with a master key supplied via env. |
| **RFC 9457 Problem Details** | `spring.mvc.problemdetails.enabled` | Unknown language → `404` with `application/problem+json`. |
| **Lombok** | `@RequiredArgsConstructor`, `@Slf4j` | Constructor injection and loggers without boilerplate. |
| **Records** | DTOs, entity, config properties | Immutable data carriers with generated accessors/equals/hashCode. |
| **DevTools** | root `pom.xml` (runtime, optional) | Auto-restart on recompile during `spring-boot:run`; excluded from the packaged jar. |
| **Actuator `info` / `health`** | `management.*`, `info.app.*` | Build, git, Java, OS and app metadata at `/actuator/info`. |
| **Custom banner** | `src/main/resources/banner.txt` | Shows service name, Boot/Java versions, port and client-auth mode at startup. |

<a id="how-mutual-tls-is-enforced"></a>
## <span style="color:hsl(331,80%,58%)">4. 🔐 How mutual TLS is enforced</span>

### <span style="color:hsl(20,80%,58%)">4.1 Key material</span>

| File (classpath)                         | Contains                                   | Used for |
|------------------------------------------|--------------------------------------------|----------|
| `ssl/service-producer-keystore.p12`      | private key + cert `CN=service-producer` (SAN `localhost`, `127.0.0.1`, `service-producer`) + CA cert | Server identity presented to callers |
| `ssl/truststore.p12`                     | demo root CA certificate only              | Validating client certificates |
| `src/test/resources/ssl/service-consumer-keystore.p12` | consumer identity | Integration test: allowed client |
| `src/test/resources/ssl/service-unknown-keystore.p12`  | CA-signed, `CN=service-unknown` | Integration test: trusted but forbidden |

Regenerate with this module's own script (shares the root CA in `../certs/out` with the consumer's script):

```bash
service-producer/src/main/resources/ssl/generate-certs.sh
```

It creates the CA on first run, issues `service-producer` (main) plus the two test-client
identities (test), and rebuilds `truststore.p12`. The script is excluded from the jar.
What a keystore, truststore, certificate and PKCS#12 are — and how the handshake uses them —
is explained in [root README — PKI, keystores, TLS handshake](../README.md#pki).

### <span style="color:hsl(80,80%,50%)">4.2 Two layers of checks</span>

| Layer | Component | Rejects | Result for caller |
|---|---|---|---|
| L4 / TLS | Tomcat + `client-auth: need` | no client cert, self-signed cert, cert from another CA, expired cert | handshake failure (`curl` exit 56, Java `SSLHandshakeException`) |
| L7 / HTTP | `ClientCertificateFilter` | CA-signed cert whose CN is not in `mtls.allowed-client-cns` | `403 Forbidden` |

The filter reads the verified chain from the standard servlet attribute
`jakarta.servlet.request.X509Certificate`, extracts the CN via `LdapName`, and stores it as
request attribute `mtls.client.cn` so the controller can echo `callerCn`.
`/actuator/health` is exempt from the CN check (the TLS layer still applies).

<a id="database"></a>
## <span style="color:hsl(165,80%,45%)">5. 🗄️ Database — PostgreSQL + Flyway</span>

PostgreSQL runs from the root [`docker-compose.yml`](../docker-compose.yml) (`postgres:19beta3`, host port **5434**).
The container starts with an empty database; **Flyway owns the schema**.

| Migration | Purpose |
|---|---|
| `V1__create_greeting_template.sql` | `greeting_template(language_code PK, template, created_at)` with a CHECK that `template` contains `%s` |
| `V2__seed_greeting_template.sql`   | Seeds `en`, `fr`, `es`, `de`, `hi` |

Add a language without code changes:

```sql
INSERT INTO greeting_template (language_code, template) VALUES ('it', 'Ciao, %s!');
```

<a id="encrypted-db-password"></a>
## <span style="color:hsl(240,80%,65%)">6. 🔑 Encrypted DB password — Jasypt `ENC(...)`</span>

<a id="jasypt-configuration"></a>
### <span style="color:hsl(20,80%,58%)">6.1 Configuration</span>

```yaml
spring:
  datasource:
    password: ENC(NO0tYiQShpiblv/VUfihJX9MT1/xeEAuV5taQv2avcJV+qFzwnjquEvz7qIJYZYC)

jasypt:
  encryptor:
    password: ${JASYPT_ENCRYPTOR_PASSWORD}      # master key — env only, never in git
    algorithm: PBEWITHHMACSHA512ANDAES_256
    key-obtention-iterations: 1000
    iv-generator-classname: org.jasypt.iv.RandomIvGenerator
```

| Value | Demo setting |
|---|---|
| Plaintext DB password | `mtls_s3cret` (same as `docker-compose.yml`) |
| Master key (`JASYPT_ENCRYPTOR_PASSWORD`) | `mtls-demo-master-key` |

The master key has **no default** — startup fails fast if the env var is missing, so a
misconfigured deployment can never silently fall back to a known key.

<a id="how-decryption-works"></a>
### <span style="color:hsl(80,80%,50%)">6.2 How decryption works at startup</span>

Spring Boot itself has no built-in property decryption; `jasypt-spring-boot-starter` plugs
into the `Environment` so decryption is transparent to every consumer of a property
(`@Value`, `@ConfigurationProperties`, auto-configuration).

```mermaid
sequenceDiagram
    participant Boot as SpringApplication
    participant BFPP as EnableEncryptablePropertiesBeanFactoryPostProcessor
    participant Env as Environment (PropertySources)
    participant Wrap as EncryptablePropertySource wrapper
    participant Det as EncryptablePropertyDetector
    participant Enc as StringEncryptor (PBE / AES-256)
    participant DS as DataSourceProperties → HikariCP

    Boot->>BFPP: context refresh (before any bean is created)
    BFPP->>Env: replace every PropertySource with an encryptable wrapper
    DS->>Wrap: getProperty("spring.datasource.password")
    Wrap->>Det: isEncrypted("ENC(NO0t…)")?
    Det-->>Wrap: yes — prefix "ENC(" and suffix ")"
    Wrap->>Enc: decrypt("NO0t…")
    Note over Enc: key = PBKDF2-HMAC-SHA512(master key, salt, 1000)<br/>plain = AES-256-CBC-decrypt(key, iv, ciphertext)
    Enc-->>Wrap: "mtls_s3cret"
    Wrap-->>DS: "mtls_s3cret" (cached)
    DS->>DS: open JDBC pool, Flyway migrates
```

1. **Auto-configuration** — the starter registers
   `EnableEncryptablePropertiesBeanFactoryPostProcessor`, which runs before any application bean
   and wraps each `PropertySource` (application.yml, env vars, system props, …).
2. **Detection** — on every `getProperty(...)`, the wrapper asks the
   `EncryptablePropertyDetector` whether the raw value is wrapped in `ENC(` … `)`.
   Plain values pass through untouched.
3. **Resolution** — the `EncryptablePropertyResolver` strips the wrapper and hands the Base64
   payload to the `StringEncryptor` bean (`jasyptStringEncryptor`, built from `jasypt.encryptor.*`).
4. **Decryption** — see 6.3. The result is cached, so decryption runs once per property.
5. **Use** — `DataSourceProperties` receives the plaintext, HikariCP opens connections and
   Flyway runs. The plaintext lives **only in JVM memory**; it is never written back to disk.

<a id="inside-enc"></a>
### <span style="color:hsl(300,70%,60%)">6.3 What is inside `ENC(...)`</span>

Algorithm `PBEWITHHMACSHA512ANDAES_256` = Password-Based Encryption: derive an AES-256 key
from the master key with PBKDF2-HMAC-SHA512, then encrypt with AES in CBC mode.

The Base64 payload of our value decodes to **48 bytes**:

```
┌──────────── 16 bytes ───────────┬──────────── 16 bytes ───────────┬──────────── 16 bytes ───────────┐
│ salt (random, per encryption)   │ IV (random, RandomIvGenerator)  │ AES-256-CBC ciphertext          │
│                                 │                                 │ "mtls_s3cret" + PKCS#5 padding  │
└─────────────────────────────────┴─────────────────────────────────┴─────────────────────────────────┘
```

| Step | Encrypt (CLI / plugin) | Decrypt (application startup) |
|---|---|---|
| 1 | generate random 16-byte **salt** | read first 16 bytes → salt |
| 2 | generate random 16-byte **IV** | read next 16 bytes → IV |
| 3 | key = PBKDF2-HMAC-SHA512(master key, salt, 1000 iterations) → 256-bit | same derivation → same key |
| 4 | ciphertext = AES-256-CBC(key, IV, plaintext) | plaintext = AES-256-CBC⁻¹(key, IV, ciphertext) |
| 5 | output Base64(salt ‖ IV ‖ ciphertext) → wrap in `ENC(...)` | — |

Consequences:

- **Same plaintext, different ciphertext every time** (random salt + IV) — you cannot tell
  whether two `ENC(...)` values hide the same password.
- **Security rests entirely on the master key.** Anyone with the ciphertext *and*
  `JASYPT_ENCRYPTOR_PASSWORD` can decrypt. Keep the key out of git, images and logs.
- `key-obtention-iterations` and `iv-generator-classname` must match between encryption and
  decryption, otherwise startup fails with `DecryptionException`.

<a id="crypto-building-blocks"></a>
### <span style="color:hsl(45,80%,50%)">6.4 Crypto building blocks explained</span>

`PBEWITHHMACSHA512ANDAES_256` is a recipe made of several standard primitives. Each one
solves a specific problem:

| Building block | What it is | Problem it solves | Value here |
|---|---|---|---|
| **Master key** (password) | Human-chosen secret, `JASYPT_ENCRYPTOR_PASSWORD` | Something only the operator knows | `mtls-demo-master-key` |
| **PBE** (Password-Based Encryption, PKCS#5 v2) | Scheme that turns a password into a cipher key | Passwords are short, low-entropy and not the right length for AES | — |
| **PBKDF2** (Key Derivation Function) | Runs a PRF many times over *password + salt* to produce key bytes | Makes each password guess expensive for an attacker | 1 000 iterations |
| **HMAC-SHA512** | Keyed hash used as PBKDF2's pseudo-random function | Mixes password and salt irreversibly; SHA-512 gives plenty of output bits | — |
| **Salt** | Random bytes stored *in clear* next to the ciphertext | Same password + different salt → different key, so pre-computed (rainbow-table) attacks and cross-value comparisons are useless | 16 random bytes per encryption |
| **Iterations** (`key-obtention-iterations`) | Number of PBKDF2 rounds | Slows brute force linearly (1 000 rounds = 1 000× the work per guess) | 1 000 (raise to ≥ 100 000 in production if startup time allows) |
| **AES-256** | Symmetric block cipher, 128-bit blocks, 256-bit key | Actual confidentiality of the data | key from PBKDF2 |
| **CBC mode** (Cipher Block Chaining) | Each plaintext block is XOR-ed with the previous ciphertext block before encryption | Identical plaintext blocks don't produce identical ciphertext blocks | — |
| **IV** (Initialisation Vector) | Random "previous block" for the first CBC block, stored in clear | Same key + same plaintext → different ciphertext; hides repeated values | 16 random bytes (`RandomIvGenerator`) |
| **PKCS#5/#7 padding** | Pads plaintext to a multiple of 16 bytes | AES-CBC only encrypts whole blocks | `mtls_s3cret` (11 B) + 5 pad bytes = 16 B |
| **Base64** | Binary-to-text encoding | Lets the bytes live inside YAML | 48 bytes → 64 chars |

**Salt vs IV** — both are random, public and stored with the ciphertext, but they feed
different stages: the **salt** changes the *key* (KDF input); the **IV** changes the
*encryption* under that key (cipher input). Neither needs to be secret — only unique/unpredictable.

**Why not just hash the password?** Hashing is one-way; the application needs the *original*
DB password to log in to PostgreSQL, so it must be reversible → encryption, not hashing.

**What this protects against / what it does not**

| ✅ Protects | ❌ Does not protect |
|---|---|
| Password leaking through git history, code review, screenshots, CI logs of `application.yml` | An attacker who also has `JASYPT_ENCRYPTOR_PASSWORD` |
| Accidental exposure of config files or container images | Heap dumps / debuggers on the running JVM (plaintext is in memory) |
| Offline brute force being cheap (salt + iterations) | A weak master key — choose a long random one |

<a id="encrypt-decrypt-a-value"></a>
### <span style="color:hsl(165,80%,45%)">6.5 Encrypt / decrypt a value</span>

Encrypt (paste `OUTPUT` into `ENC(...)`):

```bash
java -cp ~/.m2/repository/org/jasypt/jasypt/1.9.3/jasypt-1.9.3.jar \
  org.jasypt.intf.cli.JasyptPBEStringEncryptionCLI \
  input='new-db-password' password="$JASYPT_ENCRYPTOR_PASSWORD" \
  algorithm=PBEWITHHMACSHA512ANDAES_256 \
  ivGeneratorClassName=org.jasypt.iv.RandomIvGenerator keyObtentionIterations=1000
```

Decrypt (verify what a committed value contains):

```bash
java -cp ~/.m2/repository/org/jasypt/jasypt/1.9.3/jasypt-1.9.3.jar \
  org.jasypt.intf.cli.JasyptPBEStringDecryptionCLI \
  input='NO0tYiQShpiblv/VUfihJX9MT1/xeEAuV5taQv2avcJV+qFzwnjquEvz7qIJYZYC' \
  password="$JASYPT_ENCRYPTOR_PASSWORD" \
  algorithm=PBEWITHHMACSHA512ANDAES_256 ivGeneratorClassName=org.jasypt.iv.RandomIvGenerator
# ----OUTPUT----
# mtls_s3cret
```

<a id="rotating-the-db-password"></a>
### <span style="color:hsl(0,70%,60%)">6.6 Rotating the DB password</span>

1. Change the password in PostgreSQL (`ALTER ROLE mtls PASSWORD '…'`) and in `docker-compose.yml`/secret store.
2. Encrypt the new value with the **same** master key (6.5) and replace the `ENC(...)` value.
3. Restart the service.

To rotate the **master key**, decrypt every `ENC(...)` value with the old key, re-encrypt with
the new key, then deploy with the new `JASYPT_ENCRYPTOR_PASSWORD`.

<a id="jasypt-troubleshooting"></a>
### <span style="color:hsl(260,60%,65%)">6.7 Troubleshooting</span>

| Symptom | Cause |
|---|---|
| `Failed to bind properties under 'spring.datasource.password'` at startup | `JASYPT_ENCRYPTOR_PASSWORD` not set — the `ENC(...)` value cannot be decrypted |
| `EncryptionOperationNotPossibleException` / `DecryptionException` | Wrong master key, or algorithm / IV generator / iterations differ from those used to encrypt |
| `password authentication failed for user "mtls"` | Decryption worked but the plaintext does not match the DB password |

<a id="api"></a>
## <span style="color:hsl(300,70%,60%)">7. 🌐 API</span>

### `GET /api/v1/greetings/{name}?lang={code}`

| Param | In | Default | Description |
|---|---|---|---|
| `name` | path | — | Name to greet |
| `lang` | query | `en` | `language_code` in `greeting_template` |

**200**

```json
{
  "message": "Bonjour, himansu !",
  "language": "fr",
  "servedBy": "service-producer",
  "callerCn": "service-consumer",
  "timestamp": "2026-09-23T16:21:54.417Z"
}
```

| Status | When |
|---|---|
| `200` | Allowed client, language exists |
| `403` | Trusted cert, CN not allow-listed |
| `404` | Unknown `lang` (`application/problem+json`) |
| *(handshake failure)* | No / untrusted client certificate |

Actuator: `GET /actuator/health`, `GET /actuator/info` (client cert still required).

<a id="configuration-reference"></a>
## <span style="color:hsl(30,80%,55%)">8. ⚙️ Configuration reference</span>

| Env var | Default | Purpose |
|---|---|---|
| `JASYPT_ENCRYPTOR_PASSWORD` | *(required)* | Master key to decrypt `ENC(...)` values |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `5434` / `mtls_db` | JDBC URL parts |
| `DB_USERNAME` | `mtls` | DB user |
| `SSL_KEYSTORE_LOCATION` | `classpath:ssl/service-producer-keystore.p12` | Override to `file:/path` for mounted secrets |
| `SSL_TRUSTSTORE_LOCATION` | `classpath:ssl/truststore.p12` | Same |
| `KEYSTORE_PASSWORD` / `TRUSTSTORE_PASSWORD` | `changeit` | Store passwords |

| Property | Value |
|---|---|
| `server.port` | `8443` |
| `server.ssl.client-auth` | `need` |
| `mtls.allowed-client-cns` | `[service-consumer]` |

<a id="running-locally"></a>
## <span style="color:hsl(200,80%,55%)">9. 🚀 Running locally</span>

```bash
# from the repo root
docker compose up -d --wait                      # PostgreSQL 19 on :5434

cd service-producer
export JASYPT_ENCRYPTOR_PASSWORD=mtls-demo-master-key
mvn spring-boot:run                              # DevTools restarts on recompile
```

Smoke test with `curl` (PEM files are written to `certs/out/` by the generate scripts):

```bash
cd certs/out
# allowed
curl --cacert ca.crt --cert service-consumer.crt --key service-consumer.key \
     "https://localhost:8443/api/v1/greetings/himansu?lang=fr"
# 403 — trusted CA, wrong CN
curl --cacert ca.crt --cert service-unknown.crt --key service-unknown.key \
     -o /dev/null -w '%{http_code}\n' https://localhost:8443/api/v1/greetings/x
# handshake failure — no client cert
curl --cacert ca.crt https://localhost:8443/api/v1/greetings/x
```

<a id="testing"></a>
## <span style="color:hsl(120,60%,45%)">10. 🧪 Testing</span>

```bash
mvn -pl service-producer verify      # needs a Docker daemon (Testcontainers)
```

`MtlsIntegrationTest` starts the app on a random HTTPS port against a Testcontainers
`postgres:19beta3` (Flyway applies V1/V2) and asserts:

| Test | Client identity | Expected |
|---|---|---|
| `allowListedClientReceivesGreetingFromDatabase` | `service-consumer` | `200`, `"Bonjour, mtls !"` from DB |
| `unknownLanguageIsNotFound` | `service-consumer` | `404` |
| `trustedButNotAllowListedClientIsForbidden` | `service-unknown` | `403` |
| `handshakeFailsWithoutClientCertificate` | none | `ResourceAccessException` (TLS) |

<a id="project-layout"></a>
## <span style="color:hsl(260,60%,65%)">11. 📁 Project layout</span>

```
service-producer
├── pom.xml                                   parent: learning-mtls → super-pom
└── src
    ├── main
    │   ├── java/com/org/mtls/producer
    │   │   ├── ProducerApplication.java
    │   │   ├── greeting
    │   │   │   ├── GreetingController.java   GET /api/v1/greetings/{name}
    │   │   │   ├── GreetingTemplate.java     record entity
    │   │   │   └── GreetingTemplateRepository.java
    │   │   └── security
    │   │       ├── ClientCertificateFilter.java  CN allow-list
    │   │       └── MtlsProperties.java
    │   └── resources
    │       ├── application.yml
    │       ├── banner.txt
    │       ├── db/migration/V1__…, V2__…
    │       └── ssl/generate-certs.sh, service-producer-keystore.p12, truststore.p12
    └── test
        ├── java/com/org/mtls/producer
        │   ├── MtlsIntegrationTest.java
        │   └── TestcontainersConfiguration.java
        └── resources/ssl/service-consumer-keystore.p12, service-unknown-keystore.p12
```

<a id="production-notes"></a>
## <span style="color:hsl(0,80%,60%)">12. ⚠️ Production notes</span>

- The `.p12` stores in `src/*/resources/ssl` are **demo material** committed so the project runs
  out of the box. Real deployments mount stores from a secret manager and set
  `SSL_KEYSTORE_LOCATION=file:/…`. The CA private key (`certs/out/ca.key`) is never committed.
- Replace `changeit` and the demo Jasypt master key; inject both via env/secret store.
- Consider short-lived certs (cert-manager, Vault PKI, SPIFFE/SPIRE) and enabling
  `spring.ssl.bundle.jks.*.reload-on-update` with file-based stores for hot rotation.
- For richer authorization map the CN to roles with Spring Security's `x509()` support.
