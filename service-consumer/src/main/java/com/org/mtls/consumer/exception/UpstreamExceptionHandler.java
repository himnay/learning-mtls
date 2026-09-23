package com.org.mtls.consumer.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/** Maps failures talking to service-producer onto RFC 9457 problem responses. */
@Slf4j
@RestControllerAdvice
public class UpstreamExceptionHandler {

    /** Connection refused, TLS handshake failure (untrusted cert, missing client cert), timeouts. */
    @ExceptionHandler(ResourceAccessException.class)
    ProblemDetail handleIoFailure(ResourceAccessException ex) {
        log.error("Call to service-producer failed: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "service-producer unreachable or TLS handshake failed");
    }

    /** The producer has no greeting for the requested language — pass the 404 through. */
    @ExceptionHandler(HttpClientErrorException.NotFound.class)
    ProblemDetail handleNotFound(HttpClientErrorException.NotFound ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Greeting not found upstream");
    }

    /** The producer answered with any other error (e.g. 403 when our CN is not allow-listed). */
    @ExceptionHandler(RestClientResponseException.class)
    ProblemDetail handleErrorResponse(RestClientResponseException ex) {
        log.error("service-producer responded {}", ex.getStatusCode());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "service-producer responded " + ex.getStatusCode());
    }
}
