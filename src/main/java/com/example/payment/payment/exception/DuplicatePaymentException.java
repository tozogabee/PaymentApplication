package com.example.payment.payment.exception;

import java.util.UUID;

/**
 * Thrown when a create request duplicates an existing payment (same debtor, creditor, amount and
 * currency). Mapped to HTTP 409 Conflict — nothing is persisted.
 */
public class DuplicatePaymentException extends AbstractPaymentException {

    public DuplicatePaymentException(UUID existingPaymentId, String debtorAccount, String creditorAccount) {
        super("A payment already exists for debtor=%s creditor=%s (id=%s)"
                .formatted(debtorAccount, creditorAccount, existingPaymentId),
                debtorAccount, creditorAccount, null, existingPaymentId);
    }
}