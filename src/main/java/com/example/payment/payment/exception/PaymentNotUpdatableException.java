package com.example.payment.payment.exception;

import com.example.payment.api.model.PaymentStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * Thrown when a payment cannot be updated because it is not in the {@code CREATED} state.
 * Carries the message plus the debtor/creditor accounts and the current status, and is serialized
 * directly as the HTTP 409 response body (the {@link Throwable} internals are ignored).
 */
@Getter
@Setter
@AllArgsConstructor
@JsonIgnoreProperties({"cause", "stackTrace", "suppressed", "localizedMessage"})
public class PaymentNotUpdatableException extends RuntimeException {

    private String message;
    private String debtorAccount;
    private String creditorAccount;
    private PaymentStatus status;
}