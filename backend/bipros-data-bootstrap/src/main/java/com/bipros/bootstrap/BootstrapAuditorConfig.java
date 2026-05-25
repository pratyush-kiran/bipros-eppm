package com.bipros.bootstrap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;

import java.util.Optional;

/**
 * Audit metadata source for the bootstrap module. The runtime application has
 * its own {@code AuditorConfig} that pulls the actor from the security context;
 * we have no security context here, so we tag everything written by the
 * bootstrap as {@code bootstrap}. Anyone querying the audit columns will see
 * exactly when and by which actor the demo data was generated.
 */
@Configuration
public class BootstrapAuditorConfig {

    @Bean
    public AuditorAware<String> auditorProvider() {
        return () -> Optional.of("bootstrap");
    }
}
