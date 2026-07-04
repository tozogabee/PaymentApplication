package com.example.payment.payment.service;

import com.example.payment.api.model.PaymentStatus;
import com.example.payment.payment.exception.PaymentNotFoundException;
import com.example.payment.payment.exception.PaymentNotUpdatableException;
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

    @Transactional
    public PaymentCreationResult create(BigDecimal amount, String currency, String debtorAccount,
            String creditorAccount) {
        String normalizedCurrency = currency.toUpperCase();

        boolean duplicate = repository.existsByDebtorAccountAndCreditorAccountAndAmountAndCurrency(
                debtorAccount, creditorAccount, amount, normalizedCurrency);

        Optional<Payment> existingFailed =
                repository.findFirstByDebtorAccountAndCreditorAccountAndAmountAndCurrencyAndStatus(
                        debtorAccount, creditorAccount, amount, normalizedCurrency, PaymentStatus.FAILED);
        if (existingFailed.isPresent()) {
            log.error("A FAILED payment already exists (debtor={}, creditor={}, amount={} {}) "
                            + "- ignoring new create, nothing persisted",
                    debtorAccount, creditorAccount, amount, normalizedCurrency);
            return new PaymentCreationResult(existingFailed.get(), false);
        }

        if (duplicate) {
            log.error("Duplicate payment detected (debtor={}, creditor={}, amount={} {}) - marking as FAILED",
                    debtorAccount, creditorAccount, amount, normalizedCurrency);
        }
        Payment payment = Payment.builder()
                .amount(amount)
                .currency(normalizedCurrency)
                .debtorAccount(debtorAccount)
                .creditorAccount(creditorAccount)
                .status(duplicate ? PaymentStatus.FAILED : PaymentStatus.CREATED)
                .build();
        Payment saved = repository.save(payment);
        log.info("Created payment id={} status={} amount={} {}",
                saved.getId(), saved.getStatus(), saved.getAmount(), saved.getCurrency());
        return new PaymentCreationResult(saved, true);
    }

    @Transactional(readOnly = true)
    public Payment get(UUID id) {
        log.debug("Fetching payment id={}", id);
        return repository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<Payment> list() {
        List<Payment> payments = repository.findAll();
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
                    payment.getStatus());
        }
        payment.setAmount(amount);
        payment.setCurrency(currency.toUpperCase());
        payment.setDebtorAccount(debtorAccount);
        payment.setCreditorAccount(creditorAccount);
        payment.setStatus(PaymentStatus.COMPLETED);
        // Flush now so the optimistic-lock (@Version) check runs here: a concurrent update to the
        // same row throws ObjectOptimisticLockingFailureException instead of silently overwriting.
        Payment updated = this.repository.saveAndFlush(payment);
        log.info("Updated payment id={} - status transitioned to COMPLETED", id);
        return updated;
    }

    @Transactional
    public void delete(UUID id) {
        if (!repository.existsById(id)) {
            throw new PaymentNotFoundException(id);
        }
        repository.deleteById(id);
        log.info("Deleted payment id={}", id);
    }
}