package com.org.mtls.consumer.dto;

import java.time.Instant;

public record Greeting(String message, String language, String servedBy, String callerCn, Instant timestamp) {}
