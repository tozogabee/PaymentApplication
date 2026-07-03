package com.example.payment.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.example.payment.TestcontainersConfiguration;
import com.example.payment.payment.model.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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

    @Test
    void fullPaymentLifecycleAgainstPostgres() throws Exception {
        // create
        String body = mockMvc.perform(post("/payments")
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

        String id = objectMapper.readTree(body).get("id").asText();

        // read
        mockMvc.perform(get("/payments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));

        // update
        mockMvc.perform(put("/payments/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":150.0,"currency":"EUR",
                                 "debtorAccount":"DE123456789","creditorAccount":"DE987654321"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(150.0))
                .andExpect(jsonPath("$.modifiedBy").value("system"));

        // list
        String listBody = mockMvc.perform(get("/payments"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode list = objectMapper.readTree(listBody);
        assertThat(list).hasSize(1);

        // delete
        mockMvc.perform(delete("/payments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Payment deleted successfully"))
                .andExpect(jsonPath("$.id").value(id));
        mockMvc.perform(get("/payments/{id}", id))
                .andExpect(status().isNotFound());

        assertThat(repository.count()).isZero();
    }

    @Test
    void deleteUnknownPaymentReturns404() throws Exception {
        mockMvc.perform(delete("/payments/{id}", "11111111-1111-1111-1111-111111111111"))
                .andExpect(status().isNotFound());
    }
}