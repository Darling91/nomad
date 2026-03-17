-- liquibase formatted sql

-- changeset nomad:2-5
-- Процедура атомарного обновления
CREATE OR REPLACE PROCEDURE update_rate_atomic(
    p_code VARCHAR(3),
    p_source_id VARCHAR(50),
    p_new_rate DECIMAL(20,6),
    p_max_retries INTEGER DEFAULT 3
)
    LANGUAGE plpgsql
AS '
    DECLARE
        v_retry_count INTEGER := 0;
        v_updated BOOLEAN := FALSE;
        v_old_version BIGINT;
    BEGIN
        LOOP
            BEGIN
                -- Получаем текущую версию с блокировкой
                SELECT version INTO v_old_version
                FROM currency_rates
                WHERE code = p_code AND source_id = p_source_id
                    FOR UPDATE;

                -- Пытаемся обновить с проверкой версии
                UPDATE currency_rates
                SET rate = p_new_rate,
                    version = version + 1
                WHERE code = p_code
                  AND source_id = p_source_id
                  AND version = v_old_version;

                GET DIAGNOSTICS v_updated = ROW_COUNT;
                EXIT WHEN v_updated;

                -- Если не обновилось, значит версия изменилась
                v_retry_count := v_retry_count + 1;

                IF v_retry_count >= p_max_retries THEN
                    RAISE EXCEPTION ''Failed to update after % retries'', p_max_retries;
                END IF;

                -- Небольшая задержка перед повтором
                PERFORM pg_sleep(0.1 * v_retry_count);

            EXCEPTION
                WHEN serialization_failure OR deadlock_detected THEN
                    v_retry_count := v_retry_count + 1;
                    IF v_retry_count >= p_max_retries THEN
                        RAISE;
                    END IF;
                    PERFORM pg_sleep(0.1 * v_retry_count);
            END;
        END LOOP;
    END;
';