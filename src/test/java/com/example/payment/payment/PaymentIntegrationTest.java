package com.example.payment.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.example.payment.TestcontainersConfiguration;
import com.example.payment.api.model.PaymentStatus;
import com.example.payment.payment.gateway.PaymentGateway;
import com.example.payment.payment.gateway.PaymentOutcome;
import com.example.payment.payment.model.Payment;
import com.example.payment.payment.model.PaymentRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PaymentGateway gateway;

    @BeforeEach
    void clearPayments() {
        this.repository.deleteAll();
        // Default the gateway to APPROVED so update flows deterministically complete;
        // individual tests override this to exercise the declined / FAILED path.
        when(this.gateway.process(any())).thenReturn(PaymentOutcome.APPROVED);
    }

    @Test
    void fullPaymentLifecycleAgainstPostgres() throws Exception {
        String body = this.mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":100.0,"currency":"eur",
                                 "debtorAccount":"DE123456789","creditorAccount":"DE987654321"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.createdBy").value("system"))
                .andReturn().getResponse().getContentAsString();

        String id = this.objectMapper.readTree(body).get("id").asString();

        this.mockMvc.perform(get("/payments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));

        this.mockMvc.perform(put("/payments/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":150.0,"currency":"EUR",
                                 "debtorAccount":"DE123456789","creditorAccount":"DE987654321"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(150.0))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.modifiedBy").value("system"));

        this.mockMvc.perform(put("/payments/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":200.0,"currency":"EUR",
                                 "debtorAccount":"DE123456789","creditorAccount":"DE987654321"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Payment is failed"))
                .andExpect(jsonPath("$.debtorAccount").value("DE123456789"))
                .andExpect(jsonPath("$.creditorAccount").value("DE987654321"))
                .andExpect(jsonPath("$.paymentStatus").value("COMPLETED"));

        String listBody = this.mockMvc.perform(get("/payments"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode list = this.objectMapper.readTree(listBody);
        assertThat(list).hasSize(1);

        this.mockMvc.perform(delete("/payments/{id}", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("Payment %s cannot be deleted because its status is COMPLETED".formatted(id)));
        this.mockMvc.perform(get("/payments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertThat(this.repository.count()).isEqualTo(1);
    }

    @Test
    void createdPaymentCanBeDeleted() throws Exception {
        String body = this.mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":25.0,"currency":"EUR","debtorAccount":"DEL-D","creditorAccount":"DEL-C"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = this.objectMapper.readTree(body).get("id").asString();

        this.mockMvc.perform(delete("/payments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Payment deleted successfully"))
                .andExpect(jsonPath("$.id").value(id));
        this.mockMvc.perform(get("/payments/{id}", id))
                .andExpect(status().isNotFound());

        assertThat(this.repository.count()).isZero();
    }

    @Test
    void updateDeclinedByGatewayMarksPaymentFailed() throws Exception {
        when(this.gateway.process(any())).thenReturn(PaymentOutcome.DECLINED);

        String body = this.mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":80.0,"currency":"EUR",
                                 "debtorAccount":"GW-DEBTOR","creditorAccount":"GW-CREDITOR"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn().getResponse().getContentAsString();
        String id = this.objectMapper.readTree(body).get("id").asString();

        this.mockMvc.perform(put("/payments/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":80.0,"currency":"EUR",
                                 "debtorAccount":"GW-DEBTOR","creditorAccount":"GW-CREDITOR"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        // The FAILED status is persisted, not just returned.
        this.mockMvc.perform(get("/payments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void updateFailedPaymentIsRejected() throws Exception {
        Payment failed = this.repository.save(Payment.builder()
                .amount(new BigDecimal("50.00"))
                .currency("EUR")
                .debtorAccount("DE123456789")
                .creditorAccount("DE987654321")
                .status(PaymentStatus.FAILED)
                .build());

        this.mockMvc.perform(put("/payments/{id}", failed.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":150.0,"currency":"EUR",
                                 "debtorAccount":"DE123456789","creditorAccount":"DE987654321"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Payment is failed"))
                .andExpect(jsonPath("$.debtorAccount").value("DE123456789"))
                .andExpect(jsonPath("$.creditorAccount").value("DE987654321"))
                .andExpect(jsonPath("$.paymentStatus").value("FAILED"));
    }

    @Test
    void failedPaymentCanBeDeleted() throws Exception {
        Payment failed = this.repository.save(Payment.builder()
                .amount(new BigDecimal("50.00"))
                .currency("EUR")
                .debtorAccount("DE123456789")
                .creditorAccount("DE987654321")
                .status(PaymentStatus.FAILED)
                .build());

        this.mockMvc.perform(delete("/payments/{id}", failed.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Payment deleted successfully"))
                .andExpect(jsonPath("$.id").value(failed.getId().toString()));
        this.mockMvc.perform(get("/payments/{id}", failed.getId()))
                .andExpect(status().isNotFound());

        assertThat(this.repository.count()).isZero();
    }

    @Test
    void duplicateCreateIsRejectedWith409AndPersistsNothingNew() throws Exception {
        String body = """
                {"amount":77.0,"currency":"EUR",
                 "debtorAccount":"DUP-DEBTOR","creditorAccount":"DUP-CREDITOR"}
                """;

        String firstBody = this.mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn().getResponse().getContentAsString();
        String firstId = this.objectMapper.readTree(firstBody).get("id").asString();

        long countAfterFirst = this.repository.count();

        this.mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.existingPaymentId").value(firstId));

        assertThat(this.repository.count()).isEqualTo(countAfterFirst);
    }

    @Test
    void deleteUnknownPaymentReturns404() throws Exception {
        this.mockMvc.perform(delete("/payments/{id}", "11111111-1111-1111-1111-111111111111"))
                .andExpect(status().isNotFound());
    }

    @Test
    void staleUpdateIsRejectedByOptimisticLocking() {
        UUID id = this.repository.saveAndFlush(Payment.builder()
                .amount(new BigDecimal("10.00"))
                .currency("EUR")
                .debtorAccount("OPT-DEBTOR")
                .creditorAccount("OPT-CREDITOR")
                .status(PaymentStatus.CREATED)
                .build()).getId();

        Payment firstWriter = this.repository.findById(id).orElseThrow();
        Payment secondWriter = this.repository.findById(id).orElseThrow();

        firstWriter.setAmount(new BigDecimal("20.00"));
        this.repository.saveAndFlush(firstWriter);

        secondWriter.setAmount(new BigDecimal("30.00"));
        assertThatThrownBy(() -> this.repository.saveAndFlush(secondWriter))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(this.repository.findById(id).orElseThrow().getAmount()).isEqualByComparingTo("20.00");
    }
}