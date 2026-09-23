package com.org.mtls.consumer.exception;

import feign.FeignException;
import feign.RetryableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps failures talking to service-producer onto RFC 9457 problem responses. */
@Slf4j
@RestControllerAdvice
public class UpstreamExceptionHandler {

    /** Connection refused, TLS handshake failure (untrusted cert, missing client cert), timeouts. */
    @ExceptionHandler(RetryableException.class)
    ProblemDetail handleIoFailure(RetryableException ex) {
        log.error("Call to service-producer failed: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "service-producer unreachable or TLS handshake failed");
    }

    /** The producer has no greeting for the requested language — pass the 404 through. */
    @ExceptionHandler(FeignException.NotFound.class)
    ProblemDetail handleNotFound(FeignException.NotFound ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Greeting not found upstream");
    }

    /** The producer answered with any other error (e.g. 403 when our CN is not allow-listed). */
    @ExceptionHandler(FeignException.class)
    ProblemDetail handleErrorResponse(FeignException ex) {
        log.error("service-producer responded {}", ex.status());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "service-producer responded " + ex.status());
    }
}
