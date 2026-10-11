package com.spectrace.compliance.validation;

import com.spectrace.compliance.authorization.ValidationAuthorizationPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/validations")
public class ValidationController {

    private final ValidationAuthorizationPort authorization;
    private final ValidationService validations;

    public ValidationController(ValidationAuthorizationPort authorization, ValidationService validations) {
        this.authorization = authorization;
        this.validations = validations;
    }

    @PostMapping
    public ResponseEntity<ValidationRun> validate(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DraftSnapshot request) {
        ValidationAuthorizationPort.Actor actor = authorization.requireActor("LABEL.VALIDATE");
        ValidationService.Submission result = validations.evaluate(idempotencyKey, request, actor);
        return ResponseEntity.status(result.created() ? 201 : 200).body(result.run());
    }
}
