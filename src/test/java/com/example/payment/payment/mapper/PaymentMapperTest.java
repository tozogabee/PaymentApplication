package com.example.payment.payment.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.payment.api.model.PaymentResponse;
import com.example.payment.api.model.PaymentStatus;
import com.example.payment.payment.model.Payment;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentMapperTest {

    private final PaymentMapper mapper = new PaymentMapperImpl();

    @Test
    void toResponseMapsAllFields() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant modifiedAt = Instant.parse("2026-01-02T03:04:05Z");
        Payment payment = Payment.builder()
                .id(id)
                .amount(new BigDecimal("100.00"))
                .currency("EUR")
                .debtorAccount("DE123456789")
                .creditorAccount("DE987654321")
                .status(PaymentStatus.COMPLETED)
                .createdAt(createdAt)
                .createdBy("system")
                .modifiedAt(modifiedAt)
                .modifiedBy("admin")
                .build();

        PaymentResponse response = mapper.toResponse(payment);

        assertThat(response.getId()).isEqualTo(id);
        assertThat(response.getAmount()).isEqualByComparingTo("100.00");
        assertThat(response.getCurrency()).isEqualTo("EUR");
        assertThat(response.getDebtorAccount()).isEqualTo("DE123456789");
        assertThat(response.getCreditorAccount()).isEqualTo("DE987654321");
        assertThat(response.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(response.getCreatedAt()).isEqualTo(createdAt.atOffset(ZoneOffset.UTC));
        assertThat(response.getCreatedBy()).isEqualTo("system");
        assertThat(response.getModifiedAt()).isEqualTo(modifiedAt.atOffset(ZoneOffset.UTC));
        assertThat(response.getModifiedBy()).isEqualTo("admin");
    }

    @Test
    void toResponseHandlesNullTimestamps() {
        Payment payment = Payment.builder()
                .id(UUID.randomUUID())
                .amount(BigDecimal.ONE)
                .currency("EUR")
                .debtorAccount("A")
                .creditorAccount("B")
                .status(PaymentStatus.CREATED)
                .createdAt(null)
                .modifiedAt(null)
                .build();

        PaymentResponse response = mapper.toResponse(payment);

        assertThat(response.getCreatedAt()).isNull();
        assertThat(response.getModifiedAt()).isNull();
    }

    @Test
    void toResponseReturnsNullForNullPayment() {
        assertThat(mapper.toResponse(null)).isNull();
    }
}