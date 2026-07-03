package com.example.payment.payment.model;

import com.example.payment.api.model.PaymentStatus;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    boolean existsByDebtorAccountAndCreditorAccountAndAmountAndCurrency(
            String debtorAccount, String creditorAccount, BigDecimal amount, String currency);

    Optional<Payment> findFirstByDebtorAccountAndCreditorAccountAndAmountAndCurrencyAndStatus(
            String debtorAccount, String creditorAccount, BigDecimal amount, String currency, PaymentStatus status);
}