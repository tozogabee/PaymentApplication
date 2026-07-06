package com.example.payment.payment.exception;

import com.example.payment.api.model.PaymentStatus;
import java.util.UUID;
import lombok.Getter;

/**
 * Base type for all payment-domain exceptions. Carries the {@code message} (via {@link Throwable}),
 * plus the {@code debtorAccount}, {@code creditorAccount}, {@code status}, and {@code existingPaymentId}
 * involved, so subclasses and the exception handler share a consistent shape. Any of these may be
 * {@code null} when a given exception doesn't have that context; the exception handler maps them
 * onto an RFC-7807 {@code ProblemDetail} response.
 */
@Getter
public abstract class AbstractPaymentException extends RuntimeException {

    private final String debtorAccount;
    private final String creditorAccount;
    private final PaymentStatus status;
    private final UUID existingPaymentId;

    protected AbstractPaymentException(String message, String debtorAccount, String creditorAccount,
            PaymentStatus status, UUID existingPaymentId) {
        super(message);
        this.debtorAccount = debtorAccount;
        this.creditorAccount = creditorAccount;
        this.status = status;
        this.existingPaymentId = existingPaymentId;
    }
}