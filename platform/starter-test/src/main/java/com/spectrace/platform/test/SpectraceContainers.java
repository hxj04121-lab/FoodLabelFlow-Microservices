package com.spectrace.platform.test;

import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/** Container images pinned to the versions used locally (deploy/local) and in staging. */
public final class SpectraceContainers {

    public static final String MYSQL_IMAGE = "mysql:8.4.11";
    public static final String RABBITMQ_IMAGE = "rabbitmq:4.1-management";

    private SpectraceContainers() {
    }

    /** MySQL with the service's own database name, as on RDS (architecture v3 §7.1). */
    public static MySQLContainer mysql(String database) {
        return new MySQLContainer(MYSQL_IMAGE).withDatabaseName(database);
    }

    public static RabbitMQContainer rabbitmq() {
        return new RabbitMQContainer(RABBITMQ_IMAGE);
    }
}
