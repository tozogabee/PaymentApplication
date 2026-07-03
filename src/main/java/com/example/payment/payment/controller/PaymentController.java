package com.example.payment.payment.controller;

import com.example.payment.api.PaymentsApi;
import com.example.payment.api.model.DeletePaymentResponse;
import com.example.payment.api.model.PaymentRequest;
import com.example.payment.api.model.PaymentResponse;
import com.example.payment.payment.mapper.PaymentMapper;
import com.example.payment.payment.model.Payment;
import com.example.payment.payment.service.PaymentService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class PaymentController implements PaymentsApi {

    private final PaymentService service;
    private final PaymentMapper paymentMapper;

    @Override
    public ResponseEntity<PaymentResponse> createPayment(PaymentRequest request) {
        log.info("POST /payments - debtor={} creditor={}", request.getDebtorAccount(), request.getCreditorAccount());
        Payment payment = service.create(
                request.getAmount(),
                request.getCurrency(),
                request.getDebtorAccount(),
                request.getCreditorAccount());
        return ResponseEntity.status(HttpStatus.CREATED).body(paymentMapper.toResponse(payment));
    }

    @Override
    public ResponseEntity<PaymentResponse> getPaymentById(UUID id) {
        log.info("GET /payments/{}", id);
        return ResponseEntity.ok(paymentMapper.toResponse(service.get(id)));
    }

    @Override
    public ResponseEntity<List<PaymentResponse>> getAllPayments() {
        log.info("GET /payments");
        List<PaymentResponse> payments = service.list().stream()
                .map(paymentMapper::toResponse)
                .toList();
        return ResponseEntity.ok(payments);
    }

    @Override
    public ResponseEntity<PaymentResponse> updatePayment(UUID id, PaymentRequest request) {
        log.info("PUT /payments/{}", id);
        Payment payment = service.update(
                id,
                request.getAmount(),
                request.getCurrency(),
                request.getDebtorAccount(),
                request.getCreditorAccount());
        return ResponseEntity.ok(paymentMapper.toResponse(payment));
    }

    @Override
    public ResponseEntity<DeletePaymentResponse> deletePayment(UUID id) {
        log.info("DELETE /payments/{}", id);
        service.delete(id);
        return ResponseEntity.ok(new DeletePaymentResponse()
                .message("Payment deleted successfully")
                .id(id));
    }
}