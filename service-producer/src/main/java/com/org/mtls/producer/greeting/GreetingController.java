package com.org.mtls.producer.greeting;

import com.org.mtls.producer.security.ClientCertificateFilter;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/greetings")
@RequiredArgsConstructor
public class GreetingController {

    private final GreetingTemplateRepository repository;

    @GetMapping("/{name}")
    public Greeting greet(
            @PathVariable String name,
            @RequestParam(defaultValue = "en") String lang,
            @RequestAttribute(ClientCertificateFilter.CLIENT_CN_ATTRIBUTE) String clientCn) {
        GreetingTemplate template = repository.findById(lang)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No greeting template for language '" + lang + "'"));

        return new Greeting(template.template().formatted(name), lang, "service-producer", clientCn, Instant.now());
    }

    public record Greeting(String message, String language, String servedBy, String callerCn, Instant timestamp) {}
}
