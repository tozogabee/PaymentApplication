package com.example.payment.payment.mapper;

import com.example.payment.api.model.PaymentResponse;
import com.example.payment.payment.model.Payment;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.mapstruct.Mapper;

/**
 * Maps the JPA {@link Payment} entity to the OpenAPI-generated response model.
 */
@Mapper(componentModel = "spring")
public interface PaymentMapper {

    PaymentResponse toResponse(Payment payment);

    default OffsetDateTime map(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}