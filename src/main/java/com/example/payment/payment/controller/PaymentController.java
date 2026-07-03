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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PaymentController implements PaymentsApi {

    private final PaymentService service;

    @Override
    public ResponseEntity<PaymentResponse> createPayment(PaymentRequest request) {
        Payment payment = service.create(
                request.getAmount(),
                request.getCurrency(),
                request.getDebtorAccount(),
                request.getCreditorAccount());
        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentMapper.toResponse(payment));
    }

    @Override
    public ResponseEntity<PaymentResponse> getPaymentById(UUID id) {
        return ResponseEntity.ok(PaymentMapper.toResponse(service.get(id)));
    }

    @Override
    public ResponseEntity<List<PaymentResponse>> getAllPayments() {
        List<PaymentResponse> payments = service.list().stream()
                .map(PaymentMapper::toResponse)
                .toList();
        return ResponseEntity.ok(payments);
    }

    @Override
    public ResponseEntity<PaymentResponse> updatePayment(UUID id, PaymentRequest request) {
        Payment payment = service.update(
                id,
                request.getAmount(),
                request.getCurrency(),
                request.getDebtorAccount(),
                request.getCreditorAccount());
        return ResponseEntity.ok(PaymentMapper.toResponse(payment));
    }

    @Override
    public ResponseEntity<DeletePaymentResponse> deletePayment(UUID id) {
        service.delete(id);
        return ResponseEntity.ok(new DeletePaymentResponse()
                .message("Payment deleted successfully")
                .id(id));
    }
}