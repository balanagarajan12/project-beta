CREATE TABLE customer (
    customer_id   UUID         NOT NULL,
    customer_code VARCHAR(100) NOT NULL,
    customer_name VARCHAR(200) NOT NULL,
    status        VARCHAR(30)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    created_by    VARCHAR(100) NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    updated_by    VARCHAR(100) NOT NULL,
    CONSTRAINT pk_customer PRIMARY KEY (customer_id),
    CONSTRAINT uk_customer_customer_code UNIQUE (customer_code)
);
