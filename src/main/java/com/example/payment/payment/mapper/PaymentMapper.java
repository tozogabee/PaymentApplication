package com.example.payment.payment.mapper;

import com.example.payment.api.model.PaymentResponse;
import com.example.payment.payment.model.Payment;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Maps the JPA {@link Payment} entity to the OpenAPI-generated response model.
 */
public final class PaymentMapper {

    private PaymentMapper() {
    }

    public static PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse()
                .id(payment.getId())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .debtorAccount(payment.getDebtorAccount())
                .creditorAccount(payment.getCreditorAccount())
                .status(payment.getStatus())
                .createdAt(toOffsetDateTime(payment.getCreatedAt()))
                .createdBy(payment.getCreatedBy())
                .modifiedAt(toOffsetDateTime(payment.getModifiedAt()))
                .modifiedBy(payment.getModifiedBy());
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
