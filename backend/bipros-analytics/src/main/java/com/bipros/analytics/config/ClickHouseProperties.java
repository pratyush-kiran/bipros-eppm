package com.bipros.analytics.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "bipros.analytics.clickhouse")
public class ClickHouseProperties {

    private String url = "jdbc:clickhouse://bipros-clickhouse:8123/bipros_analytics";
    private String username = "bipros";
    private String password = "bipros_dev";
    private Pool pool = new Pool();

    @Getter
    @Setter
    public static class Pool {
        private int maxSize = 8;
        private int minIdle = 2;
        private long connectionTimeout = 30000L;
    }
}
