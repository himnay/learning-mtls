package com.org.mtls.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.org.mtls.producer.controller.GreetingController.Greeting;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.autoconfigure.RestClientSsl;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Boots service-producer on a random HTTPS port against a Testcontainers PostgreSQL (schema
 * applied by Flyway) and exercises the handshake with different client identities.
 * Client keystores come from {@code src/test/resources/ssl}; requires a Docker daemon.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "jasypt.encryptor.password=mtls-demo-master-key",
            "spring.ssl.bundle.jks.allowed-client.keystore.location=classpath:ssl/service-consumer-keystore.p12",
            "spring.ssl.bundle.jks.allowed-client.keystore.password=changeit",
            "spring.ssl.bundle.jks.allowed-client.keystore.type=PKCS12",
            "spring.ssl.bundle.jks.allowed-client.truststore.location=classpath:ssl/truststore.p12",
            "spring.ssl.bundle.jks.allowed-client.truststore.password=changeit",
            "spring.ssl.bundle.jks.allowed-client.truststore.type=PKCS12",
            "spring.ssl.bundle.jks.unknown-client.keystore.location=classpath:ssl/service-unknown-keystore.p12",
            "spring.ssl.bundle.jks.unknown-client.keystore.password=changeit",
            "spring.ssl.bundle.jks.unknown-client.keystore.type=PKCS12",
            "spring.ssl.bundle.jks.unknown-client.truststore.location=classpath:ssl/truststore.p12",
            "spring.ssl.bundle.jks.unknown-client.truststore.password=changeit",
            "spring.ssl.bundle.jks.unknown-client.truststore.type=PKCS12",
            "spring.ssl.bundle.jks.no-client-cert.truststore.location=classpath:ssl/truststore.p12",
            "spring.ssl.bundle.jks.no-client-cert.truststore.password=changeit",
            "spring.ssl.bundle.jks.no-client-cert.truststore.type=PKCS12"
        })
class MtlsIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private RestClient.Builder builder;

    @Autowired
    private RestClientSsl ssl;

    @Test
    void allowListedClientReceivesGreetingFromDatabase() {
        Greeting greeting = client("allowed-client")
                .get()
                .uri("/api/v1/greetings/{name}?lang={lang}", "mtls", "fr")
                .retrieve()
                .body(Greeting.class);

        assertThat(greeting).isNotNull();
        assertThat(greeting.message()).isEqualTo("Bonjour, mtls !");
        assertThat(greeting.language()).isEqualTo("fr");
        assertThat(greeting.callerCn()).isEqualTo("service-consumer");
    }

    @Test
    void unknownLanguageIsNotFound() {
        assertThatThrownBy(() -> client("allowed-client")
                        .get()
                        .uri("/api/v1/greetings/{name}?lang={lang}", "mtls", "xx")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void trustedButNotAllowListedClientIsForbidden() {
        assertThatThrownBy(() -> client("unknown-client")
                        .get()
                        .uri("/api/v1/greetings/{name}", "mtls")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void handshakeFailsWithoutClientCertificate() {
        assertThatThrownBy(() -> client("no-client-cert")
                        .get()
                        .uri("/api/v1/greetings/{name}", "mtls")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class);
    }

    private RestClient client(String bundle) {
        return builder.clone()
                .baseUrl("https://localhost:" + port)
                .apply(ssl.fromBundle(bundle))
                .build();
    }
}
