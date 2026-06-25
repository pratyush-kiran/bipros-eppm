package com.bipros.dbs.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DbsAggregationServiceRangeTest {

    @Test
    void recomputeRangeMethodExistsAndIsPublic() throws Exception {
        Method m = DbsAggregationService.class.getMethod(
                "recomputeRange", UUID.class, LocalDate.class, LocalDate.class);
        assertThat(Modifier.isPublic(m.getModifiers())).isTrue();

        Method d = DbsAggregationService.class.getMethod(
                "recomputeAllTiersForDay", UUID.class, LocalDate.class);
        assertThat(Modifier.isPublic(d.getModifiers())).isTrue();
    }
}
