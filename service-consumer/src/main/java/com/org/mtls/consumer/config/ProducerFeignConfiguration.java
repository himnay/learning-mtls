package com.org.mtls.consumer.config;

import feign.Client;
import feign.Request;
import feign.http2client.Http2Client;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.JdkHttpClientBuilder;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;

/**
 * Feign configuration of {@code ProducerClient} only. Deliberately not a {@code @Configuration}: its
 * beans live in that client's own Feign context, not in the application context.
 */
public class ProducerFeignConfiguration {

    /**
     * JDK HttpClient bound to the configured SSL bundle: the bundle's keystore supplies the client
     * certificate presented during the handshake, and its truststore validates the producer's
     * server certificate (hostname verification stays on). Only the connect timeout is set here —
     * the JDK HttpClient has no client-wide read timeout.
     */
    @Bean
    Client producerFeignClient(SslBundles sslBundles, ProducerClientProperties properties) {
        HttpClientSettings settings = HttpClientSettings.ofSslBundle(sslBundles.getBundle(properties.sslBundle()))
                .withConnectTimeout(properties.timeout());

        return new Http2Client(new JdkHttpClientBuilder().build(settings));
    }

    /** Feign passes these to every call; the read timeout becomes the JDK request timeout. */
    @Bean
    Request.Options producerFeignOptions(ProducerClientProperties properties) {
        return new Request.Options(properties.timeout(), properties.timeout(), true);
    }
}
