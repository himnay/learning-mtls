package com.org.mtls.consumer.config;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class ProducerClientConfig {

    /**
     * RestClient bound to the configured SSL bundle: the bundle's keystore supplies the client
     * certificate presented during the handshake, and its truststore validates the producer's
     * server certificate (hostname verification stays on).
     */
    @Bean
    RestClient producerRestClient(RestClient.Builder builder, SslBundles sslBundles, ProducerClientProperties properties) {
        HttpClientSettings settings = HttpClientSettings.ofSslBundle(sslBundles.getBundle(properties.sslBundle()))
                .withTimeouts(properties.timeout(), properties.timeout());

        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }
}
