package com.bipros.dbs.config;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class DbsPropertiesTest {

    @Test
    void defaultFuelRatioIs35Percent() {
        assertThat(new DbsProperties().getFuelMachineryCostRatio())
            .isEqualByComparingTo(new BigDecimal("0.35"));
    }

    @Test
    void ratioIsSettable() {
        DbsProperties p = new DbsProperties();
        p.setFuelMachineryCostRatio(new BigDecimal("0.40"));
        assertThat(p.getFuelMachineryCostRatio()).isEqualByComparingTo("0.40");
    }
}
