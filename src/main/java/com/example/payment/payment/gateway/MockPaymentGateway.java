package com.example.payment.payment.gateway;

import com.example.payment.payment.model.Payment;
import java.util.Random;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stand-in for a bank / PSP. The outcome is driven by the {@code payment.gateway.mode} property so
 * different environments can be deterministic where they need to be:
 * <ul>
 *   <li>{@code RANDOM} (default) — approve or decline at random; used for a plain local run so both
 *       the COMPLETED and FAILED paths appear;</li>
 *   <li>{@code ALWAYS_APPROVE} — always {@link PaymentOutcome#APPROVED}; used by the containerised
 *       app / CI so the end-to-end (Bruno) update tests are deterministic;</li>
 *   <li>{@code ALWAYS_DECLINE} — always {@link PaymentOutcome#DECLINED}; to demonstrate the FAILED
 *       path on demand.</li>
 * </ul>
 */
@Slf4j
@Component
public class MockPaymentGateway implements PaymentGateway {

    private final String mode;
    private final Random random = new Random();

    public MockPaymentGateway(@Value("${payment.gateway.mode:RANDOM}") String mode) {
        this.mode = mode;
    }

    @Override
    public PaymentOutcome process(Payment payment) {
        PaymentOutcome outcome = switch (this.mode.toUpperCase()) {
            case "ALWAYS_APPROVE" -> PaymentOutcome.APPROVED;
            case "ALWAYS_DECLINE" -> PaymentOutcome.DECLINED;
            default -> this.random.nextBoolean() ? PaymentOutcome.APPROVED : PaymentOutcome.DECLINED;
        };
        log.debug("Mock gateway (mode={}) returned {}", this.mode, outcome);
        return outcome;
    }
}