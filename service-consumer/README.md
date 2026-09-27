# <span style="color:hsl(200,80%,58%)">service-consumer — mTLS REST Client</span>

## <span style="color:hsl(141,80%,58%)">Table of contents</span>

1. 🧰 [Stack](#stack)
2. 🎯 [What this service does](#what-this-service-does)
3. ✨ [Features used](#features-used)
4. 🔐 [How the consumer does mutual TLS](#how-the-consumer-does-mutual-tls) — concepts: [Security, TLS & encryption](../README.md#security-goals)
5. 🌐 [API](#api)
6. 🚨 [Error mapping](#error-mapping)
7. ⚙️ [Configuration reference](#configuration-reference)
8. 🚀 [Running locally](#running-locally)
9. 🧪 [Testing](#testing)
10. 📁 [Project layout](#project-layout)
11. ⚠️ [Production notes](#production-notes)
12. ❓ [Q&A](#qa)
    - 12.1 [How do I create a keystore and a truststore from a CA-issued `.crt` file?](#qa-keystore-from-crt)
    - 12.2 [Does the CA email me the private key?](#qa-ca-private-key)
    - 12.3 [When exactly are the keystore and the truststore used?](#qa-when-stores-used)

<a id="stack"></a>
## <span style="color:hsl(278,80%,58%)">1. 🧰 Stack</span>

| Component    | Version / Detail                                                     |
|--------------|----------------------------------------------------------------------|
| Java         | 25 (`maven.compiler.release` from super-pom; needs JDK 25+)          |
| Spring Boot  | 4.1.1 (via `learning-mtls` → `super-pom`)                            |
| Spring Cloud | 2025.1.3 (via `learning-bom`): OpenFeign 5.0.3                       |
| Web          | Spring MVC on embedded Tomcat, HTTPS (port `9443`)                   |
| HTTP client  | Feign 13.6.1 `@FeignClient` on the JDK `HttpClient` (`feign-java11`) |
| JSON         | Jackson 3 (`tools.jackson`, the Spring Boot 4 default)               |
| TLS          | Spring Boot SSL bundles, PKCS12, TLS 1.3 / 1.2                       |
| Boilerplate  | Lombok + Java records                                                |
| Dev loop     | Spring Boot DevTools (auto-restart)                                  |
| Tests        | JUnit Jupiter 6, `@WebMvcTest`, Mockito                              |
| Build        | Maven 3.9+                                                           |

<a id="what-this-service-does"></a>
## <span style="color:hsl(56,80%,50%)">2. 🎯 What this service does</span>

`service-consumer` is the **client side** of the mTLS pair. It exposes
`GET /api/v1/hello/{name}` and, for each request, calls
[`service-producer`](../service-producer/README.md) over HTTPS, presenting its own client
certificate and validating the producer's server certificate.

```mermaid
flowchart LR
    user["Browser / curl"] -- "HTTPS (one-way TLS)" --> consumer
    subgraph consumer ["service-consumer :9443"]
        hc[HelloController] --> pc["ProducerClient<br/>@FeignClient"] --> rc["Feign Http2Client → JDK HttpClient<br/>SSL bundle: service-consumer"]
    end
    rc -- "HTTPS + client cert CN=service-consumer<br/>verifies server cert against truststore" --> producer["service-producer :8443<br/>client-auth=need"]
    producer --> db[(PostgreSQL)]
```

Both sides validate each other:

| Direction | Who validates | Against |
|---|---|---|
| consumer → producer server cert | consumer's Feign client (JDK `HttpClient`) | `ssl/truststore.p12` (demo root CA) + hostname (`localhost` SAN) |
| producer ← consumer client cert | producer's Tomcat + CN filter | producer truststore + `mtls.allowed-client-cns` |

<a id="features-used"></a>
## <span style="color:hsl(193,80%,58%)">3. ✨ Features used</span>

| Feature | Where | Why |
|---|---|---|
| **Spring Boot SSL bundle** (`spring.ssl.bundle.jks.service-consumer`) | `application.yml` | Single definition of identity + trust, reused for the inbound server *and* the outbound client. |
| **OpenFeign client** (`@FeignClient`) | `client/ProducerClient` | Declarative interface: one Spring MVC–annotated method per producer endpoint; Feign generates the implementation. |
| **`@EnableFeignClients(clients = ProducerClient.class)`** | `config/ProducerClientConfig` | Registers just this client. Kept off the application class so `@WebMvcTest` slices don't create Feign clients. |
| **Per-client Feign configuration** | `config/ProducerFeignConfiguration` | `feign.Client` bean: Feign's `Http2Client` over a JDK `HttpClient` that Boot's `JdkHttpClientBuilder` builds from `HttpClientSettings.ofSslBundle(...)`. It carries the client cert, trust, TLS protocols and connect timeout. `Request.Options` sets the per-request timeouts. Not a `@Configuration`, so its beans stay in this client's Feign context. |
| **`@ConfigurationProperties` record** | `config/ProducerClientProperties` | Type-safe `clients.producer.*` (base URL, bundle name, timeout with `@DefaultValue`); `@FeignClient(url = "${clients.producer.base-url}")` reads the URL. |
| **`@RestControllerAdvice` → Problem Details** | `exception/UpstreamExceptionHandler` | Handshake / IO failures → `502`, upstream `404` → `404`, other upstream errors → `502`, all as RFC 9457 JSON. |
| **Lombok** | `@RequiredArgsConstructor`, `@Slf4j` | No hand-written constructors or logger fields. |
| **Records** | `Greeting`, `HelloResponse`, properties | Immutable DTOs. |
| **DevTools** | root `pom.xml` (runtime, optional) | Auto-restart on recompile; not packaged into the jar. |
| **Actuator `info` / `health`** | `management.*`, `info.app.*` | Includes the configured downstream URL. |
| **Custom banner** | `src/main/resources/banner.txt` | Name, versions, port at startup. |

<a id="how-the-consumer-does-mutual-tls"></a>
## <span style="color:hsl(331,80%,58%)">4. 🔐 How the consumer does mutual TLS</span>

### <span style="color:hsl(20,80%,58%)">4.1 Key material</span>

**X.509** is the ITU-T standard that defines the format of a public-key certificate — a
data structure that binds a public key to an identity (the *subject*), signed by an
*issuer* (a CA), valid for a given period, and carrying extensions such as key usage and
subject alternative names. Every `.crt` file and every certificate entry inside the
`.p12` stores below is an X.509 certificate.

The subject is whatever identity the certificate was issued to. In
`service-consumer-keystore.p12` the subject is `CN=service-consumer` — this module's own
identity, presented as the client cert to the producer and also as the server cert on
`:9443`. The CA cert bundled alongside it has itself as subject (it's self-signed).

| File (classpath)                     | Contains | Used for |
|--------------------------------------|----------|----------|
| `ssl/service-consumer-keystore.p12`  | private key + cert `CN=service-consumer` (EKU `serverAuth,clientAuth`) + CA cert | Client cert sent to the producer; also server cert for `:9443` |
| `ssl/truststore.p12`                 | demo root CA only | Validating the producer's server certificate |

Regenerate with this module's own script (shares the root CA in `../certs/out` with the producer's script):

```bash
service-consumer/src/main/resources/ssl/generate-certs.sh
```

It creates the CA on first run, issues a fresh `service-consumer` key + certificate and
rebuilds `truststore.p12`. The script is excluded from the jar.

The producer's script issues its own, separate `CN=service-consumer` test certificate (in
`service-producer/src/test/resources/ssl/`), so regenerating here doesn't touch that one. Both
scripts write `../certs/out`, and the last one to run wins. Keystore vs
truststore, X.509 fields, PKCS#12 and the mTLS handshake are explained in
[root README — PKI, keystores, TLS handshake](../README.md#pki).

### <span style="color:hsl(80,80%,50%)">4.2 Wiring</span>

The client is a plain interface; Feign generates the implementation:

```java
@FeignClient(name = "service-producer", url = "${clients.producer.base-url}", configuration = ProducerFeignConfiguration.class)
public interface ProducerClient {

    @GetMapping("/api/v1/greetings/{name}")
    Greeting fetchGreeting(@PathVariable("name") String name, @RequestParam("lang") String lang);
}
```

The mTLS identity comes from the client's own Feign configuration:

```java
public class ProducerFeignConfiguration {        // deliberately not a @Configuration

    @Bean
    Client producerFeignClient(SslBundles sslBundles, ProducerClientProperties properties) {
        HttpClientSettings settings = HttpClientSettings.ofSslBundle(sslBundles.getBundle(properties.sslBundle()))
                .withConnectTimeout(properties.timeout());

        return new Http2Client(new JdkHttpClientBuilder().build(settings));
    }

    @Bean
    Request.Options producerFeignOptions(ProducerClientProperties properties) {
        return new Request.Options(properties.timeout(), properties.timeout(), true);
    }
}
```

- **Why a custom `feign.Client`?** Spring Cloud OpenFeign 5.0 has no SSL-bundle support. Its only
  TLS setting is `spring.cloud.openfeign.httpclient.disable-ssl-validation`. The client certificate
  therefore has to come from the `Client` bean.
- **Why `JdkHttpClientBuilder`?** It's Spring Boot's own bundle-to-`HttpClient` mapping. It applies
  the SSL context and the bundle's enabled protocols / ciphers, exactly as Boot does for its own
  HTTP clients. `Http2Client` (from `feign-java11`) is Feign's transport for the JDK `HttpClient`.
- **Only a connect timeout on the settings.** `JdkHttpClientBuilder` rejects a read timeout
  (`'settings' must not have a 'readTimeout'`) because the JDK `HttpClient` has no client-wide one.
  Feign's `Request.Options` supplies it per request instead.
- **Retries:** Spring Cloud OpenFeign's default `Retryer` is `NEVER_RETRY`, so a failed handshake
  is not retried.

Hostname verification stays **on**: the producer cert's SAN must match the host in
`clients.producer.base-url`.

> Spring Cloud OpenFeign is feature-complete (maintenance mode). The Spring-native alternative is
> an `@HttpExchange` interface backed by `RestClient`. Boot 4 configures those per group, SSL
> bundle included, with `spring.http.serviceclient.<group>.ssl.bundle`; no custom client bean needed.

<a id="api"></a>
## <span style="color:hsl(300,70%,60%)">5. 🌐 API</span>

### `GET /api/v1/hello/{name}?lang={code}`

| Param | In | Default | Description |
|---|---|---|---|
| `name` | path | — | Name to greet |
| `lang` | query | `en` | Passed through to the producer |

**200**

```json
{
  "consumer": "service-consumer",
  "upstream": {
    "message": "Bonjour, himansu !",
    "language": "fr",
    "servedBy": "service-producer",
    "callerCn": "service-consumer",
    "timestamp": "2026-09-23T18:41:56.596346460Z"
  }
}
```

`upstream.callerCn` is the CN the **producer** read from our client certificate — proof that mTLS happened.

<a id="error-mapping"></a>
## <span style="color:hsl(0,70%,60%)">6. 🚨 Error mapping</span>

| Upstream outcome | Exception | Consumer response |
|---|---|---|
| TLS handshake fails (untrusted producer cert, producer rejects our cert), connection refused, timeout | `feign.RetryableException` (Feign wraps the `IOException`) | `502` — `service-producer unreachable or TLS handshake failed` |
| Producer `404` (unknown language) | `FeignException.NotFound` | `404` — `Greeting not found upstream` |
| Producer `403` or any other error | `FeignException` | `502` — `service-producer responded <status>`, e.g. `service-producer responded 403` |

Every error body is `application/problem+json`, for example:

```json
{"detail":"service-producer unreachable or TLS handshake failed","instance":"/api/v1/hello/x","status":502,"title":"Bad Gateway"}
```

<a id="configuration-reference"></a>
## <span style="color:hsl(30,80%,55%)">7. ⚙️ Configuration reference</span>

| Env var | Default | Purpose |
|---|---|---|
| `PRODUCER_URL` | `https://localhost:8443` | Producer base URL |
| `SSL_KEYSTORE_LOCATION` | `classpath:ssl/service-consumer-keystore.p12` | Override to `file:/path` for mounted secrets |
| `SSL_TRUSTSTORE_LOCATION` | `classpath:ssl/truststore.p12` | Same |
| `KEYSTORE_PASSWORD` / `TRUSTSTORE_PASSWORD` | `changeit` | Store passwords |

| Property | Value |
|---|---|
| `server.port` | `9443` |
| `clients.producer.ssl-bundle` | `service-consumer` |
| `clients.producer.timeout` | `5s`: connect timeout of the JDK `HttpClient` and Feign's per-request read timeout |

<a id="running-locally"></a>
## <span style="color:hsl(200,80%,55%)">8. 🚀 Running locally</span>

Start [`service-producer`](../service-producer/README.md#running-locally) first, then:

```bash
cd service-consumer
mvn spring-boot:run                               # DevTools restarts on recompile

# from certs/out (fresh clone? create the PEMs first: root README → Quick start, step 3)
curl --cacert ca.crt "https://localhost:9443/api/v1/hello/himansu?lang=fr"
```

Verify the consumer really validates the producer. Stop the consumer, then restart it with a
truststore that does **not** contain the demo CA:

```bash
# a throwaway CA that the producer's certificate does not chain to
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=Rogue CA" \
  -keyout /tmp/rogue.key -out /tmp/rogue.crt
keytool -importcert -noprompt -alias rogue -file /tmp/rogue.crt \
  -keystore /tmp/other-truststore.p12 -storetype PKCS12 -storepass changeit

SSL_TRUSTSTORE_LOCATION=file:/tmp/other-truststore.p12 mvn spring-boot:run
curl -k https://localhost:9443/api/v1/hello/x
# 502 {"detail":"service-producer unreachable or TLS handshake failed", …}
# log: (certificate_unknown) PKIX path building failed … unable to find valid certification path to requested target executing GET https://…
```

Inbound HTTPS on `:9443` keeps working because the truststore is only used for outbound calls.
`:9443` doesn't request client certificates.

<a id="testing"></a>
## <span style="color:hsl(120,60%,45%)">9. 🧪 Testing</span>

```bash
mvn -pl service-consumer verify
```

`HelloControllerTest` (`@WebMvcTest`, `ProducerClient` mocked with `@MockitoBean`):

| Test | Expected |
|---|---|
| `wrapsUpstreamGreeting` | `200`, upstream greeting wrapped with `consumer` field |
| `handshakeFailureBecomesBadGateway` | `RetryableException` (cause `SSLHandshakeException`) → `502` problem detail |
| `upstreamNotFoundPassesThrough` | `FeignException.NotFound` → `404` problem detail |
| `upstreamForbiddenBecomesBadGateway` | `FeignException.Forbidden` → `502`, `service-producer responded 403` |

No automated test drives this module's Feign client + SSL bundle against a live producer. The mTLS
handshake is tested from the producer side: `service-producer`'s `MtlsIntegrationTest` uses the
producer module's own `CN=service-consumer` test keystore (`service-producer/src/test/resources/ssl/`).
That is a separately issued key pair, not this module's keystore. To check this module's wiring end to
end, use the `curl` flow in [Running locally](#running-locally).

<a id="project-layout"></a>
## <span style="color:hsl(260,60%,65%)">10. 📁 Project layout</span>

```
service-consumer
├── pom.xml                                     parent: learning-mtls → super-pom
└── src
    ├── main
    │   ├── java/com/org/mtls/consumer
    │   │   ├── ConsumerApplication.java
    │   │   ├── client
    │   │   │   └── ProducerClient.java              @FeignClient → GET /api/v1/greetings/{name}
    │   │   ├── config
    │   │   │   ├── ProducerClientConfig.java        @EnableFeignClients
    │   │   │   ├── ProducerClientProperties.java    clients.producer.*
    │   │   │   └── ProducerFeignConfiguration.java  feign.Client on the SSL bundle + timeouts
    │   │   ├── controller
    │   │   │   └── HelloController.java           GET /api/v1/hello/{name}
    │   │   └── exception
    │   │       └── UpstreamExceptionHandler.java  upstream failures → problem details
    │   └── resources
    │       ├── application.yml
    │       ├── banner.txt
    │       └── ssl/generate-certs.sh, service-consumer-keystore.p12, truststore.p12
    └── test/java/com/org/mtls/consumer/controller/HelloControllerTest.java
```

<a id="production-notes"></a>
## <span style="color:hsl(0,80%,60%)">11. ⚠️ Production notes</span>

- The committed `.p12` files are **demo-only**; mount real stores and set `SSL_*_LOCATION=file:/…`.
- Replace `changeit`; inject store passwords from a secret manager.
- Inbound `:9443` is one-way TLS. Add `server.ssl.client-auth: need` if callers of the consumer must also authenticate.
- For real traffic, add circuit breaking with `spring.cloud.openfeign.circuitbreaker.enabled=true` plus a
  Spring Cloud CircuitBreaker implementation (e.g. Resilience4j). Add an explicit `Retryer` only for idempotent calls.

<a id="qa"></a>
## <span style="color:hsl(190,80%,50%)">12. ❓ Q&A</span>

<a id="qa-keystore-from-crt"></a>
### <span style="color:hsl(20,80%,58%)">12.1 How do I create a keystore and a truststore from a CA-issued `.crt` file?</span>

**Q:** How can you create a keystore and a truststore from an X.509 `.crt` file issued by a CA?

**A:** The truststore needs only the CA certificate. The keystore also needs the **private key**, and a
`.crt` does not contain one: the CA only signed the public key from your CSR. The private key is
wherever the CSR was generated, either a `.key` file (openssl) or the keystore you ran
`keytool -certreq` against.

| File | `service-consumer-keystore.p12` | `truststore.p12` |
|---|---|---|
| `service-consumer.key`: private key from the CSR step | ✅ | ❌ |
| `service-consumer.crt`: certificate issued by the CA | ✅ | ❌ |
| `ca.crt`: CA certificate, plus any intermediates | ✅ as the chain | ✅ |

**Keystore, when the CSR was made with openssl** (the key is a `.key` file). This is what
`generate-certs.sh` does:

```bash
# check that key and certificate belong together: both hashes must be identical
openssl x509 -noout -pubkey -in service-consumer.crt | openssl sha256
openssl pkey -pubout -in service-consumer.key | openssl sha256

# with intermediates, bundle the chain first: cat intermediate.crt root.crt > ca.crt
openssl pkcs12 -export -name service-consumer \
  -inkey service-consumer.key -in service-consumer.crt -certfile ca.crt \
  -out service-consumer-keystore.p12 -passout pass:changeit
```

`-name` becomes the entry's alias. It must match `spring.ssl.bundle.jks.service-consumer.key.alias`
(`service-consumer`).

**Keystore, when the CSR was made with keytool** (the key already sits in a keystore). Import the CA
chain, then the signed certificate under the **same alias as the key**. That replaces the self-signed
placeholder, and the entry stays a `PrivateKeyEntry`:

```bash
keytool -importcert -noprompt -alias ca -file ca.crt \
  -keystore service-consumer-keystore.p12 -storepass changeit
keytool -importcert -alias service-consumer -file service-consumer.crt \
  -keystore service-consumer-keystore.p12 -storepass changeit
```

**Truststore**, the CA certificate only:

```bash
keytool -importcert -noprompt -alias mtls-demo-ca -file ca.crt \
  -keystore truststore.p12 -storetype PKCS12 -storepass changeit
```

The consumer's truststore must hold the CA that signed the **producer's** server certificate,
not `service-consumer.crt`. The JDK `HttpClient` also checks the producer certificate's SAN against the host in
`clients.producer.base-url`. For more CAs, repeat `-importcert` with another alias.

Ask the CA for extended key usage **`clientAuth`**. This module's certificate has
`serverAuth,clientAuth` because it is also the `:9443` server certificate. If a certificate's EKU
lacks `clientAuth`, the producer's handshake rejects it (`Extended key usage does not permit use for
TLS client authentication`). Public web CAs are phasing `clientAuth` out of
their TLS certificates, so mTLS client certificates normally come from a private CA, like the
demo CA here.

**Check the result**, then point the bundle at the new files with `SSL_KEYSTORE_LOCATION=file:/…` and
`SSL_TRUSTSTORE_LOCATION=file:/…` ([Configuration reference](#configuration-reference)):

```bash
# expect: Entry type: PrivateKeyEntry, Certificate chain length: 2 or more
keytool -list -v -keystore service-consumer-keystore.p12 -storepass changeit
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
### <span style="color:hsl(80,80%,50%)">12.2 Does the CA email me the private key?</span>

**Q:** Does the CA mail you the private key?

**A:** No. In the normal flow the CA never has the private key, so it has nothing to send. You generate
the key pair and send a **CSR** (certificate signing request): your public key and subject, signed
with your private key to prove you hold it. The CA checks who you are, signs the public key and sends
back only the certificate.

```mermaid
sequenceDiagram
    participant You as You (service-consumer host)
    participant CA
    You->>You: generate key pair → service-consumer.key (never leaves)
    You->>CA: CSR = public key + subject, signed with the private key
    CA->>CA: verify identity or domain, sign the public key
    CA-->>You: service-consumer.crt + CA chain
    You->>You: key + certificate + chain → service-consumer-keystore.p12
```

```bash
# -addext requests the SAN: this certificate is also the :9443 server certificate
openssl req -new -newkey rsa:2048 -nodes -sha256 \
  -keyout service-consumer.key -out service-consumer.csr \
  -subj "/CN=service-consumer/O=com.org" \
  -addext "subjectAltName=DNS:service-consumer,DNS:localhost,IP:127.0.0.1"
# send service-consumer.csr to the CA and keep service-consumer.key private
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
  -destkeystore service-consumer-keystore.p12 -deststoretype PKCS12
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

<a id="qa-when-stores-used"></a>
### <span style="color:hsl(300,70%,60%)">12.3 When exactly are the keystore and the truststore used?</span>

**Q:** When exactly are the truststore and the keystore used in the service-consumer microservice?

**A:** At two moments only: when the application **starts**, where both files are read, and during a
**TLS handshake**, where their in-memory copies are consulted. A request that travels over an
already-open TLS connection touches neither. Both stores belong to one SSL bundle,
`service-consumer`, which plays two roles: TLS **server** on `:9443` and TLS **client** towards the
producer.

| When | Keystore `service-consumer-keystore.p12` | Truststore `truststore.p12` |
|---|---|---|
| Application startup | read into memory (twice) | read into memory (twice) |
| A caller opens a connection to `:9443` | ✅ sends the `CN=service-consumer` certificate and signs the handshake | ❌ `:9443` asks callers for no certificate |
| First call to the producer (new connection) | ✅ answers the producer's `CertificateRequest` | ✅ checks the producer's certificate and hostname |
| Next calls over the same connection | ❌ | ❌ |
| New connection that resumes the TLS session | ❌ | ❌ |

The log messages quoted below come from a real run with
`-Djavax.net.debug=ssl:handshake:keymanager:trustmanager` on the consumer.

**1. Startup.** On the `main` thread, before `Started ConsumerApplication` is logged, Spring Boot
creates two `SSLContext`s from the bundle: one for Tomcat's `:9443` connector and one for the JDK
`HttpClient` that `ProducerFeignConfiguration` builds for Feign. Each reads both files
(`found key for : service-consumer`, `adding as trusted certificates`). A wrong path or password
therefore fails the startup, not the first request:

```
Caused by: java.lang.IllegalStateException: Could not load store from 'file:/nonexistent/truststore.p12'
```

From then on only the in-memory copies are used, so replacing a file on disk changes nothing until
a restart. With `reload-on-update: true` and `file:` locations, Spring Boot would reload the bundle
and Tomcat's `:9443` connector would pick it up, but the Feign client keeps the `SSLContext` it was
built with.

**2. A caller connects to `:9443`: keystore only.** Tomcat selects the key entry
(`matching alias: service-consumer`), sends that certificate and signs the handshake with its private
key. It sends no `CertificateRequest`, because `server.ssl.client-auth` isn't set, so the truststore
plays no part in inbound calls. That's why `:9443` keeps working in the rogue-truststore test under
[Running locally](#running-locally).

**3. First call to the producer: the mTLS handshake.** Opening the connection to the producer uses
both stores, in this order:

```mermaid
sequenceDiagram
    participant C as service-consumer (JDK HttpClient)
    participant P as service-producer :8443
    C->>P: ClientHello
    P-->>C: ServerHello, CertificateRequest
    P-->>C: Certificate CN=service-producer
    Note over C: TRUSTSTORE: does the chain end at the demo root CA?<br/>does the SAN match localhost?
    P-->>C: CertificateVerify, Finished
    Note over C: KEYSTORE: key entry service-consumer
    C->>P: Certificate CN=service-consumer
    C->>P: CertificateVerify signed with the private key, Finished
    Note over P: chain check, then the CN allow-list
    C->>P: GET /api/v1/greetings/... (encrypted)
```

1. The producer's `Certificate` message is checked against the **truststore** the moment it arrives
   (`Found trusted certificate`), together with the hostname check against its SAN.
2. After the producer's `Finished`, the **keystore** answers the `CertificateRequest`. Spring Boot's
   key manager (`AliasKeyManagerFactory`, pinned to `key.alias: service-consumer`) supplies the
   certificate chain, and the private key signs `CertificateVerify`, proving the consumer owns that
   certificate.
3. Only then does the first HTTP request leave the consumer.

**4. Every call after that: neither store.**

- The JDK `HttpClient` keeps the connection in its pool and Feign reuses it, so a call made soon after
  the previous one needs no handshake at all (in the test run, the second call opened no connection).
  The pool drops a connection after 30 s without use (`jdk.httpclient.keepalive.timeout`, JDK 25
  default); a call 50 s after the previous one needed a new connection.
- When a new connection is needed, the client resumes the TLS 1.3 session with the ticket it got
  from the first handshake (`Try resuming session`, then
  `Found resumable session. Preparing PSK message.`). No certificate travels in either direction and
  neither store is consulted, yet the producer still reports `callerCn: service-consumer`, because the
  resumed session carries the certificate from the original handshake.
- Both stores come back into play only for a **full** handshake: after either service restarts, or
  once the session ticket expires.

How to produce such a trace: [root README — TLS handshake trace](../README.md#tls-handshake-trace).
Every handshake message in detail: [root README — the mTLS handshake step by step](../README.md#the-mtls-handshake).
