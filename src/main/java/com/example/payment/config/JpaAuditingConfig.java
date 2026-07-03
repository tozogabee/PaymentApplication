package com.example.payment.config;

import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    /**
     * Supplies the current auditor for {@code @CreatedBy} / {@code @LastModifiedBy}.
     * With no security layer yet, this returns a fixed principal. Once Spring Security
     * is added, resolve it from {@code SecurityContextHolder.getContext().getAuthentication()}.
     */
    @Bean
    AuditorAware<String> auditorAware() {
        return () -> Optional.of("system");
    }
}