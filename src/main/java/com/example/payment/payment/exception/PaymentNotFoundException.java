package com.example.payment.payment.exception;

import java.util.UUID;

public class PaymentNotFoundException extends AbstractPaymentException {

    public PaymentNotFoundException(UUID id) {
        super("Payment not found: " + id, null, null, null, id);
    }
}