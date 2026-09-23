package com.org.mtls.consumer.client;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Connection settings for the downstream service-producer.
 *
 * @param baseUrl   HTTPS base URL of service-producer
 * @param sslBundle name of the {@code spring.ssl.bundle.*} entry holding the client identity and truststore
 * @param timeout   connect and read timeout
 */
@ConfigurationProperties(prefix = "clients.producer")
public record ProducerClientProperties(
        String baseUrl, String sslBundle, @DefaultValue("5s") Duration timeout) {}
