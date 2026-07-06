package com.example.payment.payment.exception;

import com.example.payment.api.model.PaymentStatus;

import java.util.UUID;

/**
 * Thrown when a payment cannot be updated because it is not in the {@code CREATED} state. Serialized
 * directly as the HTTP 409 response body (message, debtor/creditor accounts, and current status).
 */
public class PaymentNotUpdatableException extends AbstractPaymentException {

    public PaymentNotUpdatableException(String message, String debtorAccount, String creditorAccount,
                                        PaymentStatus status, UUID existingPaymentId) {
        super(message, debtorAccount, creditorAccount, status, existingPaymentId);
    }
}