package com.bipros.reporting.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvmReportData(
    String projectName,
    BigDecimal pv,
    BigDecimal ev,
    BigDecimal ac,
    BigDecimal bac,
    double spi,
    double cpi,
    BigDecimal eac,
    BigDecimal etc,
    BigDecimal vac,
    double tcpi) {}
