package com.superskidder.kylinops.backend.config;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FlywayConfig {

    @Bean(initMethod = "migrate")
    public Flyway flyway(
            DataSource dataSource,
            @Value("${spring.flyway.baseline-on-migrate:false}") boolean baselineOnMigrate,
            @Value("${spring.flyway.locations:classpath:db/migration}") String[] locations
    ) throws SQLException {
        return Flyway.configure()
                .dataSource(dataSource)
                .baselineOnMigrate(baselineOnMigrate)
                .locations(resolvedLocations(dataSource, locations))
                .load();
    }

    private String[] resolvedLocations(DataSource dataSource, String[] locations) throws SQLException {
        List<String> resolved = new ArrayList<>(Arrays.asList(locations));
        try (Connection connection = dataSource.getConnection()) {
            String databaseProduct = connection.getMetaData().getDatabaseProductName();
            if (databaseProduct != null && databaseProduct.toLowerCase().contains("postgresql")) {
                resolved.add("classpath:db/postgresql");
            }
        }
        return resolved.toArray(String[]::new);
    }
}
