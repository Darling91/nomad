-- liquibase formatted sql

-- changeset nomad:2-2
-- Триггер для автоматического обновления updated_at
DROP TRIGGER IF EXISTS update_currency_rates_updated_at ON currency_rates;
CREATE TRIGGER update_currency_rates_updated_at
    BEFORE UPDATE ON currency_rates
    FOR EACH ROW
EXECUTE FUNCTION update_updated_at_column();