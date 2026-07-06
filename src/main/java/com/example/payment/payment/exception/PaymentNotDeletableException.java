package com.example.payment.payment.exception;

import com.example.payment.api.model.PaymentStatus;
import java.util.UUID;

/**
 * Thrown when a delete is attempted on a payment whose status forbids it (a {@code COMPLETED} payment
 * must be kept for audit). Mapped to HTTP 409 Conflict.
 */
public class PaymentNotDeletableException extends AbstractPaymentException {

    public PaymentNotDeletableException(UUID id, PaymentStatus status) {
        super("Payment %s cannot be deleted because its status is %s".formatted(id, status),
                null, null, status, id);
    }
}