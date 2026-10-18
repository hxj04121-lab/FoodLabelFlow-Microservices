package com.spectrace.platform.test;

import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Minimal MySQL Testcontainers implementation.
 *
 * <p>Keeping the container type in our namespace avoids the false-positive CPE mapping from the
 * {@code testcontainers-mysql} jar to the MySQL server product. The behaviour is the subset used by
 * this repository and follows the MIT-licensed Testcontainers MySQL implementation.
 */
public class SpectraceMySqlContainer extends JdbcDatabaseContainer<SpectraceMySqlContainer> {

    private static final DockerImageName DEFAULT_IMAGE_NAME = DockerImageName.parse("mysql");
    private static final String DEFAULT_USER = "test";
    private static final String DEFAULT_PASSWORD = "test";
    private static final String ROOT_USER = "root";
    private static final int MYSQL_PORT = 3306;

    private String databaseName = "test";
    private String username = DEFAULT_USER;
    private String password = DEFAULT_PASSWORD;

    public SpectraceMySqlContainer(String dockerImageName) {
        this(DockerImageName.parse(dockerImageName));
    }

    public SpectraceMySqlContainer(DockerImageName dockerImageName) {
        super(dockerImageName);
        dockerImageName.assertCompatibleWith(DEFAULT_IMAGE_NAME);
        addExposedPort(MYSQL_PORT);
    }

    @Override
    protected void configure() {
        addEnv("MYSQL_DATABASE", databaseName);
        if (!ROOT_USER.equalsIgnoreCase(username)) {
            addEnv("MYSQL_USER", username);
        }
        if (password != null && !password.isEmpty()) {
            addEnv("MYSQL_PASSWORD", password);
            addEnv("MYSQL_ROOT_PASSWORD", password);
        } else if (ROOT_USER.equalsIgnoreCase(username)) {
            addEnv("MYSQL_ALLOW_EMPTY_PASSWORD", "yes");
        } else {
            throw new ContainerLaunchException("Empty password can be used only with the root user");
        }
        setStartupAttempts(3);
    }

    @Override
    public String getDriverClassName() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            return "com.mysql.cj.jdbc.Driver";
        } catch (ClassNotFoundException e) {
            return "com.mysql.jdbc.Driver";
        }
    }

    @Override
    public String getJdbcUrl() {
        return "jdbc:mysql://" + getHost() + ":" + getMappedPort(MYSQL_PORT) + "/" + databaseName
                + constructUrlParameters("?", "&");
    }

    @Override
    protected String constructUrlForConnection(String queryString) {
        String url = super.constructUrlForConnection(queryString);
        if (!url.contains("useSSL=")) {
            url += (url.contains("?") ? "&" : "?") + "useSSL=false";
        }
        if (!url.contains("allowPublicKeyRetrieval=")) {
            url += "&allowPublicKeyRetrieval=true";
        }
        return url;
    }

    @Override
    public String getDatabaseName() {
        return databaseName;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    protected String getTestQueryString() {
        return "SELECT 1";
    }

    @Override
    public SpectraceMySqlContainer withDatabaseName(String value) {
        databaseName = value;
        return self();
    }

    @Override
    public SpectraceMySqlContainer withUsername(String value) {
        username = value;
        return self();
    }

    @Override
    public SpectraceMySqlContainer withPassword(String value) {
        password = value;
        return self();
    }
}
