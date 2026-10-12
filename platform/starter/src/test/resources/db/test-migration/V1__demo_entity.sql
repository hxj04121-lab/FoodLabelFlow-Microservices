-- A stand-in for a service's own state table in the messaging tests.
CREATE TABLE demo_entity (
    id   VARCHAR(64)  NOT NULL,
    name VARCHAR(100) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE demo_projection (
    aggregate_id VARCHAR(128) NOT NULL,
    version      BIGINT       NOT NULL,
    applied      INT          NOT NULL,
    PRIMARY KEY (aggregate_id)
);
