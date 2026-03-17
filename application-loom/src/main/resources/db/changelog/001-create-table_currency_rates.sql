CREATE TABLE IF NOT EXISTS currency_rates (
                                id BIGSERIAL PRIMARY KEY,
                                code VARCHAR(3) NOT NULL,
                                rate DECIMAL(20,6) NOT NULL,
                                source_id VARCHAR(50) NOT NULL,
                                updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                version BIGINT NOT NULL DEFAULT 1
);
