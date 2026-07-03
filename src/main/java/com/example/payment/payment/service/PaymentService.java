package com.example.payment.payment.service;

import com.example.payment.payment.exception.PaymentNotFoundException;
import com.example.payment.payment.model.Payment;
import com.example.payment.payment.model.PaymentRepository;
import com.example.payment.payment.model.PaymentStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository repository;

    @Transactional
    public Payment create(BigDecimal amount, String currency, String debtorAccount, String creditorAccount) {
        Payment payment = Payment.builder()
                .amount(amount)
                .currency(currency.toUpperCase())
                .debtorAccount(debtorAccount)
                .creditorAccount(creditorAccount)
                .status(PaymentStatus.CREATED)
                .build();
        return repository.save(payment);
    }

    @Transactional(readOnly = true)
    public Payment get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<Payment> list() {
        return repository.findAll();
    }

    @Transactional
    public Payment update(UUID id, BigDecimal amount, String currency, String debtorAccount, String creditorAccount) {
        Payment payment = get(id);
        payment.setAmount(amount);
        payment.setCurrency(currency.toUpperCase());
        payment.setDebtorAccount(debtorAccount);
        payment.setCreditorAccount(creditorAccount);
        return payment;
    }

    @Transactional
    public void delete(UUID id) {
        if (!repository.existsById(id)) {
            throw new PaymentNotFoundException(id);
        }
        repository.deleteById(id);
    }
}