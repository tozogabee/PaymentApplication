package com.example.payment.payment.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.payment.payment.exception.PaymentNotFoundException;
import com.example.payment.payment.model.Payment;
import com.example.payment.api.model.PaymentStatus;
import com.example.payment.payment.service.PaymentService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PaymentController.class)
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
        when(service.create(any(), any(), any(), any())).thenReturn(samplePayment(id));

        mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":100.0,"currency":"EUR",
                                 "debtorAccount":"DE123456789","creditorAccount":"DE987654321"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.currency").value("EUR"));

        verify(service).create(new BigDecimal("100.0"), "EUR", "DE123456789", "DE987654321");
    }

    @Test
    void createPaymentWithInvalidBodyReturns400() throws Exception {
        mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":-1,"currency":"EURO",
                                 "debtorAccount":"","creditorAccount":"x"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getUnknownPaymentReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.get(eq(id))).thenThrow(new PaymentNotFoundException(id));

        mockMvc.perform(get("/payments/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletePaymentReturns200WithMessageAndId() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/payments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Payment deleted successfully"))
                .andExpect(jsonPath("$.id").value(id.toString()));

        verify(service).delete(id);
    }
}