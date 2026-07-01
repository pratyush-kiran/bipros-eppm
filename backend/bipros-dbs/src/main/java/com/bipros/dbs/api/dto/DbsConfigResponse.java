package com.bipros.dbs.api.dto;

import java.math.BigDecimal;

/**
 * App-wide DBS tunables exposed to the UI. Currently just the fuel/machinery cost ratio
 * ({@code bipros.dbs.fuel-machinery-cost-ratio}) so the DPR totals bar can derive
 * Fuel cost = ratio × equipment cost without hardcoding the fraction.
 */
public record DbsConfigResponse(BigDecimal fuelMachineryCostRatio) {}
