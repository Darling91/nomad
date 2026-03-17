-- liquibase formatted sql

-- changeset nomad:2-1
-- Функция для обновления updated_at
CREATE OR REPLACE FUNCTION update_updated_at_column()
    RETURNS TRIGGER AS '
    BEGIN
        NEW.updated_at = NOW();
        RETURN NEW;
    END;
' LANGUAGE plpgsql;