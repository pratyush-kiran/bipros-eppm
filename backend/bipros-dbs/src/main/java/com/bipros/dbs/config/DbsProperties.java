package com.bipros.dbs.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Code-side DBS tunables (no UI). Change values in application.yml under {@code bipros.dbs}
 * and restart — no recompile needed.
 */
@Component
@ConfigurationProperties(prefix = "bipros.dbs")
@Getter
@Setter
public class DbsProperties {

    /**
     * Section D (Fuel) is derived as this fraction of the Section C (Machinery) cost.
     * Decimal fraction: 0.35 = 35%. Client rule as of 2026-06.
     */
    private BigDecimal fuelMachineryCostRatio = new BigDecimal("0.35");
}
