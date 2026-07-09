package com.example.payment.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.payment.payment.model.Payment;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MockPaymentGatewayTest {

    private final Payment payment = Payment.builder()
            .amount(new BigDecimal("10.00"))
            .currency("EUR")
            .debtorAccount("D")
            .creditorAccount("C")
            .build();

    @Test
    void alwaysApproveModeApproves() {
        assertThat(new MockPaymentGateway("ALWAYS_APPROVE").process(this.payment))
                .isEqualTo(PaymentOutcome.APPROVED);
    }

    @Test
    void alwaysDeclineModeDeclines() {
        assertThat(new MockPaymentGateway("ALWAYS_DECLINE").process(this.payment))
                .isEqualTo(PaymentOutcome.DECLINED);
    }

    @Test
    void randomModeReturnsSomeOutcome() {
        assertThat(new MockPaymentGateway("RANDOM").process(this.payment))
                .isIn(PaymentOutcome.APPROVED, PaymentOutcome.DECLINED);
    }

    @Test
    void unknownModeFallsBackToRandom() {
        assertThat(new MockPaymentGateway("something-else").process(this.payment))
                .isIn(PaymentOutcome.APPROVED, PaymentOutcome.DECLINED);
    }
}