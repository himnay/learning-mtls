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
13. ❓ [Q&A](#qa)
    - 13.1 [How do I create a keystore and a truststore from a CA-issued `.crt` file?](#qa-keystore-from-crt)
    - 13.2 [Does the CA email me the private key?](#qa-ca-private-key)
    - 13.3 [Does a service need a keystore if it only calls another service, or only serves one?](#qa-keystore-by-role)

<a id="stack"></a>
## <span style="color:hsl(278,80%,58%)">1. 🧰 Stack</span>

| Component         | Version / Detail                                                        |
|-------------------|-------------------------------------------------------------------------|
| Java              | 25 (`maven.compiler.release` from super-pom; needs JDK 25+)             |
| Spring Boot       | 4.1.1 (via `learning-mtls` → `super-pom`)                               |
| Web               | Spring MVC on embedded Tomcat, HTTPS only (port `8443`)                 |
| TLS               | Spring Boot SSL bundles, PKCS12, TLS 1.3 / 1.2                          |
| Persistence       | Spring Data JDBC + PostgreSQL 19 (beta3), HikariCP                      |
| Migrations        | Flyway 12 (`spring-boot-starter-flyway` + `flyway-database-postgresql`) |
| Secret encryption | Jasypt Spring Boot 4.0.4 (`PBEWITHHMACSHA512ANDAES_256`)                |
| Boilerplate       | Lombok + Java records                                                   |
| Dev loop          | Spring Boot DevTools (auto-restart)                                     |
| Tests             | JUnit Jupiter 6, Testcontainers 2 (PostgreSQL), RestClient              |
| Build             | Maven 3.9+                                                              |

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
    T-->>C: ServerHello + CertificateRequest + service-producer cert + CertificateVerify
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
| **CN allow-list filter** | `filter/ClientCertificateFilter` | Transport trust ≠ authorization. Any cert from the CA passes TLS; only listed CNs reach the controller (others get `403`). |
| **[`@ConfigurationProperties`][ConfigurationProperties] record** | `config/MtlsProperties` | Immutable, type-safe binding of `mtls.allowed-client-cns`. |
| **Spring Data JDBC with a record entity** | `entites/GreetingTemplate`, `repository/GreetingTemplateRepository` | Zero-boilerplate read model; no JPA/Hibernate needed for a lookup table. |
| **Flyway migrations** | `src/main/resources/db/migration` | Schema (`V1`) and seed data (`V2`) are versioned and applied on startup. |
| **Jasypt `ENC(...)`** | `spring.datasource.password` | DB password is stored encrypted in YAML; decrypted in memory at startup with a master key supplied via env. |
| **RFC 9457 Problem Details** | `spring.mvc.problemdetails.enabled` | Unknown language → `404` with `application/problem+json`. |
| **Lombok** | [`@RequiredArgsConstructor`][RequiredArgsConstructor], [`@Slf4j`][Slf4j] | Constructor injection and loggers without boilerplate. |
| **Records** | DTOs, entity, config properties | Immutable data carriers with generated accessors/equals/hashCode. |
| **DevTools** | root `pom.xml` (runtime, optional) | Auto-restart on recompile during `spring-boot:run`; excluded from the packaged jar. |
| **Actuator `info` / `health`** | `management.*`, `info.app.*` | Build, git, Java, OS and app metadata at `/actuator/info`. |
| **Custom banner** | `src/main/resources/banner.txt` | Shows service name, Boot/Java versions, port and client-auth mode at startup. |

<a id="how-mutual-tls-is-enforced"></a>
## <span style="color:hsl(331,80%,58%)">4. 🔐 How mutual TLS is enforced</span>

### <span style="color:hsl(20,80%,58%)">4.1 Key material</span>

**X.509** is the ITU-T standard that defines the format of a public-key certificate — a
data structure that binds a public key to an identity (the *subject*), signed by an
*issuer* (a CA), valid for a given period, and carrying extensions such as key usage and
subject alternative names. Every `.crt` file and every certificate entry inside the
`.p12` stores below is an X.509 certificate.

The subject is whatever identity the certificate was issued to — the CA's own cert has
itself as subject, and each leaf cert's subject is the service or test identity it was
issued for. In `service-producer-keystore.p12` the subject is `CN=service-producer`
(this module's own server identity); the other entries below carry their own subjects,
e.g. `CN=service-consumer` and `CN=service-unknown` for the test-client certs.

| File | Contains | Used for |
|---|---|---|
| `src/main/resources/ssl/service-producer-keystore.p12` | private key + cert `CN=service-producer` (SAN `localhost`, `127.0.0.1`, `service-producer`) + CA cert | Server identity presented to callers |
| `src/main/resources/ssl/truststore.p12` | demo root CA certificate only | Validating client certificates |
| `src/test/resources/ssl/service-consumer-keystore.p12` | key + cert `CN=service-consumer`, issued by **this** module's script. It's a separate key pair from the consumer module's own keystore | Integration test: allowed client |
| `src/test/resources/ssl/service-unknown-keystore.p12` | CA-signed, `CN=service-unknown` | Integration test: trusted but forbidden |

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
| L4 / TLS | Tomcat + `client-auth: need` | no client cert, self-signed cert, cert from another CA, expired cert | handshake failure (`curl` exit 56, Java [`SSLHandshakeException`][SSLHandshakeException]) |
| L7 / HTTP | `ClientCertificateFilter` | CA-signed cert whose CN is not in `mtls.allowed-client-cns` | `403 Forbidden` |

Tomcat's check is standard X.509 path validation (JSSE) against `truststore.p12`. It walks the
presented cert's issuer chain and confirms it ends at the demo root CA entry. It then checks that
every cert in the chain is correctly signed and within its validity period, and that the leaf's key
usage / extended key usage allow TLS client authentication. That's all it checks: **it has no
notion of the Common Name**, and it does no hostname matching for client certs. A
trusted-but-unauthorized cert like `service-unknown` (§4.1) passes the handshake without issue;
identity/authorization is entirely the filter's job, one layer up.

The filter reads the verified chain from the standard servlet attribute
`jakarta.servlet.request.X509Certificate`, extracts the CN via [`LdapName`][LdapName], and stores it as
request attribute `mtls.client.cn` so the controller can echo `callerCn`.
`/actuator/health` is exempt from the CN check (the TLS layer still applies); `/actuator/info` is not.

<a id="database"></a>
## <span style="color:hsl(165,80%,45%)">5. 🗄️ Database — PostgreSQL + Flyway</span>

PostgreSQL runs from the root [`docker-compose.yml`](../docker-compose.yml) (`postgres:19beta3`, host port **5434**).
The container starts with an empty database; **Flyway owns the schema**.

| Migration | Purpose |
|---|---|
| `V1__create_greeting_template.sql` | `greeting_template(language_code PK, template, created_at)` with a CHECK that `template` contains `%s` |
| `V2__seed_greeting_template.sql`   | Seeds `en`, `fr`, `es`, `de`, `hi` |

Add a language without code changes. No restart is needed, because every request reads the table:

```bash
docker exec mtls-postgres psql -U mtls -d mtls_db \
  -c "INSERT INTO greeting_template (language_code, template) VALUES ('it', 'Ciao, %s!');"
```

To keep it reproducible, ship the row as a new migration instead (e.g. `V3__add_italian.sql`).

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

The master key has **no default**. If the env var is missing, Spring leaves the placeholder
unresolved, Jasypt uses the literal text `${JASYPT_ENCRYPTOR_PASSWORD}` as the key, decryption
fails and startup aborts. A misconfigured deployment therefore never falls back silently to a known
key, but the error looks the same as a wrong key ([6.7](#jasypt-troubleshooting)). In an IDE, add
the variable to the run configuration's environment (IntelliJ: *Run → Edit Configurations… →
ProducerApplication → Environment variables*).

<a id="how-decryption-works"></a>
### <span style="color:hsl(80,80%,50%)">6.2 How decryption works at startup</span>

Spring Boot itself has no built-in property decryption; `jasypt-spring-boot-starter` plugs
into the [`Environment`][Environment] so decryption is transparent to every consumer of a property
([`@Value`][Value], [`@ConfigurationProperties`][ConfigurationProperties], auto-configuration).

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
   [`EnableEncryptablePropertiesBeanFactoryPostProcessor`][EnableEncryptablePropertiesBeanFactoryPostProcessor], which runs before any application bean
   and wraps each [`PropertySource`][PropertySource] (application.yml, env vars, system props, …).
2. **Detection** — on every `getProperty(...)`, the wrapper asks the
   [`EncryptablePropertyDetector`][EncryptablePropertyDetector] whether the raw value is wrapped in `ENC(` … `)`.
   Plain values pass through untouched.
3. **Resolution** — the [`EncryptablePropertyResolver`][EncryptablePropertyResolver] strips the wrapper and hands the Base64
   payload to the [`StringEncryptor`][StringEncryptor] bean (`jasyptStringEncryptor`, built from `jasypt.encryptor.*`).
4. **Decryption** — see 6.3. The result is cached, so decryption runs once per property.
5. **Use** — [`DataSourceProperties`][DataSourceProperties] receives the plaintext, HikariCP opens connections and
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
  decryption, otherwise startup fails with [`DecryptionException`][DecryptionException].

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
| **Iterations** (`key-obtention-iterations`) | Number of PBKDF2 rounds | Slows brute force linearly (1 000 rounds = 1 000× the work per guess) | 1 000. Raise it in production: OWASP suggests 210 000 for PBKDF2-HMAC-SHA512, and it only runs once per property at startup |
| **AES-256** | Symmetric block cipher, 128-bit blocks, 256-bit key | Actual confidentiality of the data | key from PBKDF2 |
| **CBC mode** (Cipher Block Chaining) | Each plaintext block is XOR-ed with the previous ciphertext block before encryption | Identical plaintext blocks don't produce identical ciphertext blocks | — |
| **IV** (Initialisation Vector) | Random "previous block" for the first CBC block, stored in clear | Same key + same plaintext → different ciphertext; hides repeated values | 16 random bytes ([`RandomIvGenerator`][RandomIvGenerator]) |
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
  algorithm=PBEWITHHMACSHA512ANDAES_256 \
  ivGeneratorClassName=org.jasypt.iv.RandomIvGenerator keyObtentionIterations=1000
# ----OUTPUT----
# mtls_s3cret
```

<a id="rotating-the-db-password"></a>
### <span style="color:hsl(0,70%,60%)">6.6 Rotating the DB password</span>

1. Change the password in PostgreSQL (`ALTER ROLE mtls PASSWORD '…'`), then in `docker-compose.yml` / your
   secret store. `POSTGRES_PASSWORD` only applies when the volume is first initialised, so `ALTER ROLE` is
   what actually changes it.
2. Encrypt the new value with the **same** master key (6.5) and replace the `ENC(...)` value.
3. Restart the service.

To rotate the **master key**, decrypt every `ENC(...)` value with the old key, re-encrypt with
the new key, then deploy with the new `JASYPT_ENCRYPTOR_PASSWORD`.

<a id="jasypt-troubleshooting"></a>
### <span style="color:hsl(260,60%,65%)">6.7 Troubleshooting</span>

| Symptom | Cause |
|---|---|
| `APPLICATION FAILED TO START` · `Failed to bind properties under 'spring.datasource.password' to java.lang.String` | `JASYPT_ENCRYPTOR_PASSWORD` **unset or wrong**, or algorithm / IV generator / iterations differ from those used to encrypt. Unset and wrong look identical, because an unset variable leaves the literal `${JASYPT_ENCRYPTOR_PASSWORD}` as the key |
| `password authentication failed for user "mtls"` | Decryption worked but the plaintext does not match the DB password |

The real cause is only logged at debug level. To see it, start with
`LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_BOOT_DIAGNOSTICS=debug` (or
`--logging.level.org.springframework.boot.diagnostics=debug`). The output then shows
`DecryptionException: Unable to decrypt property: ENC(…) … Decryption of Properties failed, make sure
encryption/decryption passwords match`, caused by `EncryptionOperationNotPossibleException`.

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
  "timestamp": "2026-09-23T18:41:56.691413916Z"
}
```

| Status | When |
|---|---|
| `200` | Allowed client, language exists |
| `403` | Trusted cert, CN not allow-listed. The body is Spring Boot's default error JSON (`application/json`), not a problem detail, because the filter rejects the request before Spring MVC |
| `404` | Unknown `lang` (`application/problem+json`, `detail`: `No greeting template for language 'xx'`) |
| *(handshake failure)* | No / untrusted client certificate: TLS alert, no HTTP response |

Actuator (a client cert is always required): `GET /actuator/health` accepts any CA-signed cert
because it's exempt from the CN check. `GET /actuator/info` needs an allow-listed CN.

<a id="configuration-reference"></a>
## <span style="color:hsl(30,80%,55%)">8. ⚙️ Configuration reference</span>

| Env var | Default | Purpose |
|---|---|---|
| `JASYPT_ENCRYPTOR_PASSWORD` | *(required)* | Master key to decrypt `ENC(...)` values |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `5434` / `mtls_db` | JDBC URL parts |
| `DB_USERNAME` | `mtls` | DB user |
| `SPRING_DATASOURCE_PASSWORD` | `ENC(...)` in `application.yml` | Overrides the DB password, plain or `ENC(...)` (env values are decrypted too) |
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

In an IDE, set the same variable in the run configuration ([6.1](#jasypt-configuration)).

Smoke test with `curl`. The PEM files live in `certs/out` at the repo root, which is git-ignored; on a fresh clone,
create them first ([root README → Quick start](../README.md#quick-start), step 3):

```bash
cd certs/out                                     # at the repo root
# allowed
curl --cacert ca.crt --cert service-consumer.crt --key service-consumer.key \
     "https://localhost:8443/api/v1/greetings/himansu?lang=fr"
# 403 — trusted CA, wrong CN
curl --cacert ca.crt --cert service-unknown.crt --key service-unknown.key \
     -o /dev/null -w '%{http_code}\n' https://localhost:8443/api/v1/greetings/x
# handshake failure — no client cert
curl --cacert ca.crt https://localhost:8443/api/v1/greetings/x
# curl: (56) … tlsv13 alert certificate required
```

<a id="testing"></a>
## <span style="color:hsl(120,60%,45%)">10. 🧪 Testing</span>

```bash
mvn -pl service-producer verify      # needs a Docker daemon (Testcontainers)
```

`MtlsIntegrationTest` starts the app on a random HTTPS port against a Testcontainers
`postgres:19beta3` (Flyway applies V1/V2). It sets `jasypt.encryptor.password` itself, so no env var
is needed. Client identities come from `src/test/resources/ssl`, and the main `truststore.p12`
doubles as the clients' truststore. Because the class name ends in `Test`, Surefire runs it in the
`test` phase, so `mvn test` needs Docker as well. It asserts:

| Test | Client identity | Expected |
|---|---|---|
| `allowListedClientReceivesGreetingFromDatabase` | `service-consumer` | `200`, `"Bonjour, mtls !"` from DB |
| `unknownLanguageIsNotFound` | `service-consumer` | `404` |
| `trustedButNotAllowListedClientIsForbidden` | `service-unknown` | `403` |
| `handshakeFailsWithoutClientCertificate` | none | [`ResourceAccessException`][ResourceAccessException] (TLS) |

<a id="project-layout"></a>
## <span style="color:hsl(260,60%,65%)">11. 📁 Project layout</span>

```
service-producer
├── pom.xml                                   parent: learning-mtls → super-pom
└── src
    ├── main
    │   ├── java/com/org/mtls/producer
    │   │   ├── ProducerApplication.java
    │   │   ├── config
    │   │   │   └── MtlsProperties.java           mtls.allowed-client-cns
    │   │   ├── controller
    │   │   │   └── GreetingController.java       GET /api/v1/greetings/{name}
    │   │   ├── entites
    │   │   │   └── GreetingTemplate.java         record entity
    │   │   ├── filter
    │   │   │   └── ClientCertificateFilter.java  CN allow-list
    │   │   └── repository
    │   │       └── GreetingTemplateRepository.java
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
  `SSL_KEYSTORE_LOCATION=file:/…`. The CA private key (`../certs/out/ca.key`) is never committed.
- Replace `changeit` and the demo Jasypt master key; inject both via env/secret store.
- Consider short-lived certs (cert-manager, Vault PKI, SPIFFE/SPIRE) and enabling
  `spring.ssl.bundle.jks.*.reload-on-update` with file-based stores for hot rotation.
- For richer authorization map the CN to roles with Spring Security's `x509()` support.

<a id="qa"></a>
## <span style="color:hsl(190,80%,50%)">13. ❓ Q&A</span>

<a id="qa-keystore-from-crt"></a>
### <span style="color:hsl(20,80%,58%)">13.1 How do I create a keystore and a truststore from a CA-issued `.crt` file?</span>

**Q:** How can you create a keystore and a truststore from an X.509 `.crt` file issued by a CA?

**A:** The truststore needs only the CA certificate. The keystore also needs the **private key**, and a
`.crt` does not contain one: the CA only signed the public key from your CSR. The private key is
wherever the CSR was generated, either a `.key` file (openssl) or the keystore you ran
`keytool -certreq` against.

| File | `service-producer-keystore.p12` | `truststore.p12` |
|---|---|---|
| `service-producer.key`: private key from the CSR step | ✅ | ❌ |
| `service-producer.crt`: certificate issued by the CA | ✅ | ❌ |
| `ca.crt`: CA certificate, plus any intermediates | ✅ as the chain | ✅ |

**Keystore, when the CSR was made with openssl** (the key is a `.key` file). This is what
`generate-certs.sh` does:

```bash
# check that key and certificate belong together: both hashes must be identical
openssl x509 -noout -pubkey -in service-producer.crt | openssl sha256
openssl pkey -pubout -in service-producer.key | openssl sha256

# with intermediates, bundle the chain first: cat intermediate.crt root.crt > ca.crt
openssl pkcs12 -export -name service-producer \
  -inkey service-producer.key -in service-producer.crt -certfile ca.crt \
  -out service-producer-keystore.p12 -passout pass:changeit
```

`-name` becomes the entry's alias. It must match `spring.ssl.bundle.jks.service-producer.key.alias`
(`service-producer`).

**Keystore, when the CSR was made with keytool** (the key already sits in a keystore). Import the CA
chain, then the signed certificate under the **same alias as the key**. That replaces the self-signed
placeholder, and the entry stays a `PrivateKeyEntry`:

```bash
keytool -importcert -noprompt -alias ca -file ca.crt \
  -keystore service-producer-keystore.p12 -storepass changeit
keytool -importcert -alias service-producer -file service-producer.crt \
  -keystore service-producer-keystore.p12 -storepass changeit
```

**Truststore**, the CA certificate only:

```bash
keytool -importcert -noprompt -alias mtls-demo-ca -file ca.crt \
  -keystore truststore.p12 -storetype PKCS12 -storepass changeit
```

The producer's truststore must hold the CA that signed its **callers'** certificates, not
`service-producer.crt`. Trusting a CA accepts every certificate that CA issues, which is why the CN
allow-list ([4.2](#how-mutual-tls-is-enforced)) is still needed. For more CAs, repeat `-importcert` with
another alias.

**Check the result**, then point the bundle at the new files with `SSL_KEYSTORE_LOCATION=file:/…` and
`SSL_TRUSTSTORE_LOCATION=file:/…` ([Configuration reference](#configuration-reference)):

```bash
# expect: Entry type: PrivateKeyEntry, Certificate chain length: 2 or more
keytool -list -v -keystore service-producer-keystore.p12 -storepass changeit
# expect: one trustedCertEntry per CA
keytool -list -keystore truststore.p12 -storepass changeit
```

**Traps**

- **Lost private key:** it cannot be recovered. Generate a new key and CSR, and ask the CA to reissue
  the certificate (rekey).
- **DER instead of PEM** (binary, no `-----BEGIN`): convert it with
  `openssl x509 -inform der -in cert.cer -out cert.crt`.
- **Chain missing from the keystore:** clients that don't have the intermediate fail with
  `PKIX path building failed`.
- **mTLS:** each side has its own keystore (its own key and certificate), and each side's truststore
  holds the CA that signed the *other* side's certificate.

Keystore vs truststore and the store formats are covered in
[root README — keystores, truststores and file formats](../README.md#stores-and-formats).

<a id="qa-ca-private-key"></a>
### <span style="color:hsl(80,80%,50%)">13.2 Does the CA email me the private key?</span>

**Q:** Does the CA mail you the private key?

**A:** No. In the normal flow the CA never has the private key, so it has nothing to send. You generate
the key pair and send a **CSR** (certificate signing request): your public key and subject, signed
with your private key to prove you hold it. The CA checks who you are, signs the public key and sends
back only the certificate.

```mermaid
sequenceDiagram
    participant You as You (service-producer host)
    participant CA
    You->>You: generate key pair → service-producer.key (never leaves)
    You->>CA: CSR = public key + subject, signed with the private key
    CA->>CA: verify identity or domain, sign the public key
    CA-->>You: service-producer.crt + CA chain
    You->>You: key + certificate + chain → service-producer-keystore.p12
```

```bash
# -addext requests the SAN: the consumer checks the host it dialled against the SAN
openssl req -new -newkey rsa:2048 -nodes -sha256 \
  -keyout service-producer.key -out service-producer.csr \
  -subj "/CN=service-producer/O=com.org" \
  -addext "subjectAltName=DNS:service-producer,DNS:localhost,IP:127.0.0.1"
# send service-producer.csr to the CA and keep service-producer.key private
```

`generate-certs.sh` plays both roles on one machine. It creates the key and CSR, then signs the CSR
with the demo CA's `ca.key`, which stays in the git-ignored `certs/out` at the repo root. No private
key ever travels.

**Exception: the CA generates the key for you.** Some CAs, enterprise PKI portals (for example
Microsoft AD CS web enrollment or Venafi) and cloud consoles can create the key pair on their side.
You then download a password-protected `.pfx`/`.p12` holding key, certificate and chain. That file
already is a keystore: point `SSL_KEYSTORE_LOCATION` at it and set `key.alias` to its entry (see
`keytool -list`), or convert it:

```bash
keytool -importkeystore -srckeystore download.pfx -srcstoretype PKCS12 \
  -destkeystore service-producer-keystore.p12 -deststoretype PKCS12
```

> ⚠️ Whoever generated the key has seen it. Treat a private key sent **by email** as compromised:
> mail servers and inboxes keep copies. Prefer the CSR flow. If server-side generation is
> unavoidable, download over HTTPS and protect the file with a strong password.

**So where is my key?** Wherever the CSR was made:

- **openssl:** the `.key` file next to the `.csr`.
- **keytool:** inside the `.jks`/`.p12` used with `keytool -certreq`.
- **IIS / Windows:** in the Windows certificate store. Complete the certificate request, then export
  a `.pfx`.
- **A teammate or ops made the CSR:** ask them.

Not found anywhere? Then it is lost. Create a new key and CSR and ask the CA to reissue the
certificate (rekey); most CAs do that free of charge while the certificate is still valid.

<a id="qa-keystore-by-role"></a>
### <span style="color:hsl(300,70%,60%)">13.3 Does a service need a keystore if it only calls another service, or only serves one?</span>

**Q:** Does a service need a keystore if it only consumes an external service but doesn't expose
any REST API, or the other way round?

**A:** It depends on which side has to prove its identity, not on which way the calls go. A
**keystore** holds the service's *own* private key and certificate. It's needed whenever *this*
service must prove who it is in a TLS handshake. A **truststore** holds the CA certificates used to
check *the other side*.

| Service | TLS mode | Keystore | Truststore |
|---|---|---|---|
| Only calls other services | one-way TLS (ordinary HTTPS) | ❌ not needed | only if the server's CA isn't already trusted by the JDK (`cacerts`), e.g. a private CA like the demo CA here |
| Only calls other services | mTLS: the server asks for a client certificate | ✅ its client certificate and key | same rule as above |
| Only serves an API | HTTPS | ✅ its server certificate and key | ❌ not needed |
| Only serves an API | mTLS (`client-auth: need`) | ✅ | ✅ the CAs whose client certificates it accepts |
| Only serves an API, TLS ends in front of it (ingress, load balancer, service mesh) | plain HTTP inside | ❌ | ❌ |

- **A pure client using ordinary HTTPS needs no keystore.** Calling a public API only requires
  trusting the server's CA, and the JDK's default truststore already holds the public CAs. There is
  nothing to configure.
- **A pure client needs a keystore as soon as the server asks for a client certificate.** Without one,
  it answers the `CertificateRequest` with an empty certificate, and a server running
  `client-auth: need` aborts the handshake with `certificate_required`.
- **A pure server always needs a keystore for HTTPS**, because it must present a certificate and sign
  the handshake with its private key. It needs a truststore only if it verifies client certificates.

**What that means for this module.** The producer is the "only serves an API" case. It calls no other
service over HTTPS; its only outbound connection is JDBC to PostgreSQL.

- It needs `service-producer-keystore.p12` because it serves HTTPS on `:8443`.
- It needs `truststore.p12` only because it runs `client-auth: need` and must verify the callers'
  certificates. With one-way HTTPS the truststore could go.
- The consumer is the mirror case. It still needs a keystore for its calls, because the producer asks
  for a client certificate.

In Spring Boot, an SSL bundle may hold just one of the two stores:

- **Truststore only:** gives an HTTP client trust in a private CA without presenting a client
  certificate.
- **Keystore only:** trust falls back to the JDK's default truststore. [`DefaultSslManagerBundle`][DefaultSslManagerBundle]
  initialises the [`TrustManagerFactory`][TrustManagerFactory] with `null`, and `null` means the JDK default.
- **Server side:** `server.ssl.bundle` needs the keystore. Add a truststore and
  `server.ssl.client-auth: need` only for mTLS.

<!-- Library classes mentioned above, linked to their source at the versions this project builds with. -->

[ConfigurationProperties]: https://github.com/spring-projects/spring-boot/blob/v4.1.1/core/spring-boot/src/main/java/org/springframework/boot/context/properties/ConfigurationProperties.java
[DataSourceProperties]: https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-jdbc/src/main/java/org/springframework/boot/jdbc/autoconfigure/DataSourceProperties.java
[DecryptionException]: https://github.com/ulisesbocchio/jasypt-spring-boot/blob/jasypt-spring-boot-parent-4.0.4/jasypt-spring-boot/src/main/java/com/ulisesbocchio/jasyptspringboot/exception/DecryptionException.java
[DefaultSslManagerBundle]: https://github.com/spring-projects/spring-boot/blob/v4.1.1/core/spring-boot/src/main/java/org/springframework/boot/ssl/DefaultSslManagerBundle.java
[EnableEncryptablePropertiesBeanFactoryPostProcessor]: https://github.com/ulisesbocchio/jasypt-spring-boot/blob/jasypt-spring-boot-parent-4.0.4/jasypt-spring-boot/src/main/java/com/ulisesbocchio/jasyptspringboot/configuration/EnableEncryptablePropertiesBeanFactoryPostProcessor.java
[EncryptablePropertyDetector]: https://github.com/ulisesbocchio/jasypt-spring-boot/blob/jasypt-spring-boot-parent-4.0.4/jasypt-spring-boot/src/main/java/com/ulisesbocchio/jasyptspringboot/EncryptablePropertyDetector.java
[EncryptablePropertyResolver]: https://github.com/ulisesbocchio/jasypt-spring-boot/blob/jasypt-spring-boot-parent-4.0.4/jasypt-spring-boot/src/main/java/com/ulisesbocchio/jasyptspringboot/EncryptablePropertyResolver.java
[Environment]: https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-core/src/main/java/org/springframework/core/env/Environment.java
[LdapName]: https://github.com/openjdk/jdk/blob/jdk-25-ga/src/java.naming/share/classes/javax/naming/ldap/LdapName.java
[PropertySource]: https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-core/src/main/java/org/springframework/core/env/PropertySource.java
[RandomIvGenerator]: https://github.com/jasypt/jasypt/blob/jasypt-1.9.3/jasypt/src/main/java/org/jasypt/iv/RandomIvGenerator.java
[RequiredArgsConstructor]: https://github.com/projectlombok/lombok/blob/v1.18.46/src/core/lombok/RequiredArgsConstructor.java
[ResourceAccessException]: https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-web/src/main/java/org/springframework/web/client/ResourceAccessException.java
[Slf4j]: https://github.com/projectlombok/lombok/blob/v1.18.46/src/core/lombok/extern/slf4j/Slf4j.java
[SSLHandshakeException]: https://github.com/openjdk/jdk/blob/jdk-25-ga/src/java.base/share/classes/javax/net/ssl/SSLHandshakeException.java
[StringEncryptor]: https://github.com/jasypt/jasypt/blob/jasypt-1.9.3/jasypt/src/main/java/org/jasypt/encryption/StringEncryptor.java
[TrustManagerFactory]: https://github.com/openjdk/jdk/blob/jdk-25-ga/src/java.base/share/classes/javax/net/ssl/TrustManagerFactory.java
[Value]: https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-beans/src/main/java/org/springframework/beans/factory/annotation/Value.java
