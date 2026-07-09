package com.example.payment.payment.gateway;

import com.example.payment.payment.model.Payment;

/**
 * Port to an external payment processor (a bank / PSP). Given a payment, it decides whether the
 * money movement is {@link PaymentOutcome#APPROVED} or {@link PaymentOutcome#DECLINED}. A real
 * adapter would call the processor's API; {@link MockPaymentGateway} stands in for that here.
 */
public interface PaymentGateway {

    PaymentOutcome process(Payment payment);
}