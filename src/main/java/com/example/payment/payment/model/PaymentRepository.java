package com.example.payment.payment.model;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    boolean existsByDebtorAccountAndCreditorAccountAndAmountAndCurrency(
            String debtorAccount, String creditorAccount, BigDecimal amount, String currency);
}