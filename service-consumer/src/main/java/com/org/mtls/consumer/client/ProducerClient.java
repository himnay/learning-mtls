package com.org.mtls.consumer.client;

import com.org.mtls.consumer.config.ProducerFeignConfiguration;
import java.time.Instant;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Declarative OpenFeign client for service-producer. The transport — and with it the mTLS client
 * identity — comes from {@link ProducerFeignConfiguration}.
 */
@FeignClient(name = "service-producer", url = "${clients.producer.base-url}", configuration = ProducerFeignConfiguration.class)
public interface ProducerClient {

    @GetMapping("/api/v1/greetings/{name}")
    Greeting fetchGreeting(@PathVariable("name") String name, @RequestParam("lang") String lang);

    record Greeting(String message, String language, String servedBy, String callerCn, Instant timestamp) {}
}
