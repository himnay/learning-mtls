package com.org.mtls.consumer.client;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class ProducerClient {

    private final RestClient producerRestClient;

    public Greeting fetchGreeting(String name, String lang) {
        return producerRestClient.get()
                .uri("/api/v1/greetings/{name}?lang={lang}", name, lang)
                .retrieve()
                .body(Greeting.class);
    }

    public record Greeting(String message, String language, String servedBy, String callerCn, Instant timestamp) {}
}
