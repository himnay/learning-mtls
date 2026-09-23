package com.org.mtls.producer.greeting;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/** Row of {@code greeting_template}; {@code template} is a {@link String#formatted} pattern with one {@code %s}. */
@Table("greeting_template")
public record GreetingTemplate(@Id String languageCode, String template) {}
