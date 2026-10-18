package com.spectrace.platform.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ContainerLaunchException;

class SpectraceMySqlContainerTest {

    @Test
    void exposesTheRepositoryMySqlContractWithoutStartingDocker() {
        SpectraceMySqlContainer container = new SpectraceMySqlContainer("mysql:8.4.11")
                .withDatabaseName("orders")
                .withUsername("label_user")
                .withPassword("secret");

        assertThat(container.getDatabaseName()).isEqualTo("orders");
        assertThat(container.getUsername()).isEqualTo("label_user");
        assertThat(container.getPassword()).isEqualTo("secret");
        assertThat(container.getTestQueryString()).isEqualTo("SELECT 1");
        assertThat(container.getDriverClassName()).isIn("com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver");
        assertThat(container.getExposedPorts()).containsExactly(3306);

        container.configure();
        assertThat(container.getEnvMap())
                .containsEntry("MYSQL_DATABASE", "orders")
                .containsEntry("MYSQL_USER", "label_user")
                .containsEntry("MYSQL_PASSWORD", "secret")
                .containsEntry("MYSQL_ROOT_PASSWORD", "secret");
        assertThat(container.getStartupAttempts()).isEqualTo(3);
    }

    @Test
    void buildsJdbcUrlsAndSupportsTheRootEmptyPasswordMode() {
        SpectraceMySqlContainer container = new SpectraceMySqlContainer("mysql:8.4.11") {
            @Override
            public String getHost() {
                return "127.0.0.1";
            }

            @Override
            public Integer getMappedPort(int originalPort) {
                return 3307;
            }
        }.withDatabaseName("orders")
                .withUsername("root")
                .withPassword("")
                .withUrlParam("connectTimeout", "2000");

        assertThat(container.getJdbcUrl())
                .isEqualTo("jdbc:mysql://127.0.0.1:3307/orders?connectTimeout=2000");
        assertThat(container.constructUrlForConnection(""))
                .isEqualTo("jdbc:mysql://127.0.0.1:3307/orders?connectTimeout=2000"
                        + "&useSSL=false&allowPublicKeyRetrieval=true");
        assertThat(container.constructUrlForConnection("?useSSL=true"))
                .isEqualTo("jdbc:mysql://127.0.0.1:3307/orders?connectTimeout=2000&useSSL=true"
                        + "&allowPublicKeyRetrieval=true");

        container.configure();
        assertThat(container.getEnvMap()).containsEntry("MYSQL_ALLOW_EMPTY_PASSWORD", "yes");
    }

    @Test
    void rejectsAnEmptyPasswordForANonRootUser() {
        SpectraceMySqlContainer container = new SpectraceMySqlContainer("mysql:8.4.11")
                .withUsername("label_user")
                .withPassword("");

        assertThatThrownBy(container::configure)
                .isInstanceOf(ContainerLaunchException.class)
                .hasMessageContaining("root user");
    }
}
