package com.example.payment.payment.service;

import com.example.payment.payment.model.Payment;

/**
 * Result of a create request. {@code created} is {@code true} when a new payment was persisted, and
 * {@code false} when the request was ignored (a FAILED duplicate already existed) and the existing
 * payment is returned instead.
 */
public record PaymentCreationResult(Payment payment, boolean created) {
}