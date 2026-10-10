package com.spectrace.platform.test;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

/**
 * {@code @Import(MySqlTestcontainers.class)}: a MySQL 8.4 container wired to the service's DataSource
 * and Flyway. The database is named after {@code spectrace.test.database} (default {@code service}).
 */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlTestcontainers {

    @Bean
    @ServiceConnection
    MySQLContainer spectraceMySqlContainer(@Value("${spectrace.test.database:service}") String database) {
        return SpectraceContainers.mysql(database);
    }
}
