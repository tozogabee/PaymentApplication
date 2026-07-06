package com.example.payment.payment.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.payment.payment.exception.DuplicatePaymentException;
import com.example.payment.payment.exception.PaymentNotDeletableException;
import com.example.payment.payment.exception.PaymentNotFoundException;
import com.example.payment.payment.exception.PaymentNotUpdatableException;
import com.example.payment.payment.mapper.PaymentMapperImpl;
import com.example.payment.payment.model.Payment;
import com.example.payment.api.model.PaymentStatus;
import com.example.payment.payment.service.PaymentService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PaymentController.class)
@Import(PaymentMapperImpl.class)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService service;

    private Payment samplePayment(UUID id) {
        return Payment.builder()
                .id(id)
                .amount(new BigDecimal("100.00"))
                .currency("EUR")
                .debtorAccount("DE123456789")
                .creditorAccount("DE987654321")
                .status(PaymentStatus.CREATED)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .createdBy("system")
                .build();
    }

    @Test
    void createPaymentReturns201() throws Exception {
        UUID id = UUID.randomUUID();
        when(this.service.create(any(), any(), any(), any()))
                .thenReturn(this.samplePayment(id));

        this.mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":100.0,"currency":"EUR",
                                 "debtorAccount":"DE123456789","creditorAccount":"DE987654321"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.currency").value("EUR"));

        verify(this.service).create(new BigDecimal("100.0"), "EUR", "DE123456789", "DE987654321");
    }

    @Test
    void createDuplicatePaymentReturns409() throws Exception {
        UUID existingId = UUID.randomUUID();
        when(this.service.create(any(), any(), any(), any()))
                .thenThrow(new DuplicatePaymentException(existingId, "DE123456789", "DE987654321"));

        this.mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":100.0,"currency":"EUR",
                                 "debtorAccount":"DE123456789","creditorAccount":"DE987654321"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.existingPaymentId").value(existingId.toString()));
    }

    @Test
    void createPaymentWithInvalidBodyReturns400() throws Exception {
        this.mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":-1,"currency":"EURO",
                                 "debtorAccount":"","creditorAccount":"x"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateAlreadyCompletedPaymentReturns409() throws Exception {
        UUID id = UUID.randomUUID();
        when(this.service.update(eq(id), any(), any(), any(), any()))
                .thenThrow(new PaymentNotUpdatableException(
                        "Payment is failed", "DE123456789", "DE987654321", PaymentStatus.COMPLETED, id));

        this.mockMvc.perform(put("/payments/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":150.0,"currency":"EUR",
                                 "debtorAccount":"DE123456789","creditorAccount":"DE987654321"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Payment is failed"))
                .andExpect(jsonPath("$.debtorAccount").value("DE123456789"))
                .andExpect(jsonPath("$.creditorAccount").value("DE987654321"))
                .andExpect(jsonPath("$.paymentStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.existingPaymentId").value(id.toString()));
    }

    @Test
    void concurrentUpdateConflictReturns409() throws Exception {
        UUID id = UUID.randomUUID();
        when(this.service.update(eq(id), any(), any(), any(), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Payment.class, id));

        this.mockMvc.perform(put("/payments/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":150.0,"currency":"EUR",
                                 "debtorAccount":"DE123456789","creditorAccount":"DE987654321"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The payment was modified by another request; please retry."));
    }

    @Test
    void getUnknownPaymentReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(this.service.get(eq(id))).thenThrow(new PaymentNotFoundException(id));

        this.mockMvc.perform(get("/payments/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void unexpectedErrorReturns500() throws Exception {
        UUID id = UUID.randomUUID();
        when(this.service.get(eq(id))).thenThrow(new RuntimeException("boom"));

        this.mockMvc.perform(get("/payments/{id}", id))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
    }

    @Test
    void deletePaymentReturns200WithMessageAndId() throws Exception {
        UUID id = UUID.randomUUID();

        this.mockMvc.perform(delete("/payments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Payment deleted successfully"))
                .andExpect(jsonPath("$.id").value(id.toString()));

        verify(this.service).delete(id);
    }

    @Test
    void deleteCompletedPaymentReturns409() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new PaymentNotDeletableException(id, PaymentStatus.COMPLETED))
                .when(this.service).delete(id);

        this.mockMvc.perform(delete("/payments/{id}", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("Payment %s cannot be deleted because its status is COMPLETED".formatted(id)))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.paymentStatus").value("COMPLETED"));
    }
}