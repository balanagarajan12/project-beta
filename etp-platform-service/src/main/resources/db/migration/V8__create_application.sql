CREATE TABLE application (
    application_id   UUID         NOT NULL,
    customer_id      UUID         NOT NULL,
    application_code VARCHAR(100) NOT NULL,
    application_name VARCHAR(200) NOT NULL,
    description      TEXT,
    application_type VARCHAR(50),
    lifecycle_status VARCHAR(30)  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(100) NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    updated_by       VARCHAR(100) NOT NULL,
    CONSTRAINT pk_application PRIMARY KEY (application_id),
    CONSTRAINT uk_application_customer_id_application_code UNIQUE (customer_id, application_code),
    CONSTRAINT fk_application_customer_id FOREIGN KEY (customer_id) REFERENCES customer (customer_id)
);

CREATE INDEX idx_application_customer_id ON application (customer_id);
