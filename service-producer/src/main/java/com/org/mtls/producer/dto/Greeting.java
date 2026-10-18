package com.org.mtls.producer.dto;

import java.time.Instant;

public record Greeting(String message, String language, String servedBy, String callerCn, Instant timestamp) {}
