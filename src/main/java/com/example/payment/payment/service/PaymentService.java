package com.example.payment.payment.service;

import com.example.payment.api.model.PaymentStatus;
import com.example.payment.payment.exception.DuplicatePaymentException;
import com.example.payment.payment.exception.PaymentNotDeletableException;
import com.example.payment.payment.exception.PaymentNotFoundException;
import com.example.payment.payment.exception.PaymentNotUpdatableException;
import com.example.payment.payment.gateway.PaymentGateway;
import com.example.payment.payment.gateway.PaymentOutcome;
import com.example.payment.payment.model.Payment;
import com.example.payment.payment.model.PaymentRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository repository;
    private final PaymentGateway gateway;

    @Transactional
    public Payment create(BigDecimal amount, String currency, String debtorAccount, String creditorAccount) {
        String normalizedCurrency = currency.toUpperCase();

        Optional<Payment> existing = this.repository.findFirstByDebtorAccountAndCreditorAccountAndAmountAndCurrency(
                debtorAccount, creditorAccount, amount, normalizedCurrency);
        if (existing.isPresent()) {
            log.warn("Duplicate payment rejected (debtor={}, creditor={}, amount={} {}) - existing id={}",
                    debtorAccount, creditorAccount, amount, normalizedCurrency, existing.get().getId());
            throw new DuplicatePaymentException(existing.get().getId(), debtorAccount, creditorAccount);
        }

        Payment payment = Payment.builder()
                .amount(amount)
                .currency(normalizedCurrency)
                .debtorAccount(debtorAccount)
                .creditorAccount(creditorAccount)
                .status(PaymentStatus.CREATED)
                .build();
        Payment saved = this.repository.save(payment);
        log.info("Created payment id={} status={} amount={} {}",
                saved.getId(), saved.getStatus(), saved.getAmount(), saved.getCurrency());
        return saved;
    }

    @Transactional(readOnly = true)
    public Payment get(UUID id) {
        log.debug("Fetching payment id={}", id);
        return this.repository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<Payment> list() {
        List<Payment> payments = this.repository.findAll();
        log.debug("Listing payments, count={}", payments.size());
        return payments;
    }

    @Transactional
    public Payment update(UUID id, BigDecimal amount, String currency, String debtorAccount, String creditorAccount) {
        Payment payment = this.get(id);
        if (payment.getStatus() != PaymentStatus.CREATED) {
            log.error("Update rejected for payment id={} because status is {} (only CREATED is updatable)",
                    id, payment.getStatus());
            throw new PaymentNotUpdatableException(
                    "Payment is failed",
                    payment.getDebtorAccount(),
                    payment.getCreditorAccount(),
                    payment.getStatus(),
                    id);
        }
        payment.setAmount(amount);
        payment.setCurrency(currency.toUpperCase());
        payment.setDebtorAccount(debtorAccount);
        payment.setCreditorAccount(creditorAccount);

        // Submit the payment to the gateway (simulates a bank / PSP): approved money movement
        // completes the payment, a declined one marks it as failed.
        PaymentOutcome outcome = this.gateway.process(payment);
        payment.setStatus(outcome == PaymentOutcome.APPROVED ? PaymentStatus.COMPLETED : PaymentStatus.FAILED);

        Payment updated = this.repository.saveAndFlush(payment);
        log.info("Processed payment id={} - gateway outcome={} -> status={}", id, outcome, updated.getStatus());
        return updated;
    }

    @Transactional
    public void delete(UUID id) {
        Payment payment = this.repository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException(id));
        if (payment.getStatus() == PaymentStatus.COMPLETED) {
            log.warn("Delete rejected for payment id={} because it is COMPLETED", id);
            throw new PaymentNotDeletableException(id, payment.getStatus());
        }
        this.repository.delete(payment);
        log.info("Deleted payment id={}", id);
    }
}