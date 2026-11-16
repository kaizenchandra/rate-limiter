package com.example.ratelimiter.api;

import com.example.ratelimiter.application.PolicyCache;
import com.example.ratelimiter.infrastructure.persistence.PolicyRepository;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ErrorHandler {
    @ExceptionHandler(PolicyCache.Unavailable.class)
    ResponseEntity<ProblemDetail> unavailable() {
        return error(503, "Rate limiter unavailable; do not automatically retry evaluations");
    }

    @ExceptionHandler(PolicyRepository.Conflict.class)
    ResponseEntity<ProblemDetail> conflict() {
        return error(409, "Policy version conflict");
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    ResponseEntity<ProblemDetail> invalid() {
        return error(400, "Invalid request");
    }

    private ResponseEntity<ProblemDetail> error(int code, String detail) {
        return ResponseEntity.status(code)
                .body(ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(code), detail));
    }
}
