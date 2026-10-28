CREATE TABLE activation_request (
    id              UUID                     PRIMARY KEY,
    client_id       VARCHAR(128)             NOT NULL,
    external_id     VARCHAR(64)              NOT NULL,
    customer_id     VARCHAR(64)              NOT NULL,
    product_code    VARCHAR(64)              NOT NULL,
    status          VARCHAR(20)              NOT NULL,
    attempts        INTEGER                  NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    failure_reason  VARCHAR(500),
    correlation_id  VARCHAR(64),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    version         BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT uk_activation_request_client_external_id UNIQUE (client_id, external_id),
    CONSTRAINT ck_activation_request_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_activation_request_attempts CHECK (attempts >= 0)
);

CREATE INDEX ix_activation_request_due
    ON activation_request (status, next_attempt_at)
    WHERE status IN ('PENDING', 'PROCESSING');
CREATE INDEX ix_activation_request_client_status
    ON activation_request (client_id, status, created_at DESC);

CREATE INDEX ix_activation_request_client_customer
    ON activation_request (client_id, customer_id, created_at DESC);
