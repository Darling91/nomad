-- liquibase formatted sql

-- changeset nomad:2-4
-- Функция-обёртка для удобного вызова
CREATE OR REPLACE FUNCTION calculate_rate_delta_func(p_code VARCHAR(3))
    RETURNS TABLE(
                     nbk_rate DECIMAL(20,6),
                     xe_rate DECIMAL(20,6),
                     delta DECIMAL(20,6),
                     delta_percent DECIMAL(10,4),
                     last_update_nbk TIMESTAMPTZ,
                     last_update_xe TIMESTAMPTZ,
                     status VARCHAR(50),
                     message TEXT
                 ) AS '
    DECLARE
        v_nbk_rate DECIMAL(20,6);
        v_xe_rate DECIMAL(20,6);
        v_delta DECIMAL(20,6);
        v_delta_percent DECIMAL(10,4);
        v_last_update_nbk TIMESTAMPTZ;
        v_last_update_xe TIMESTAMPTZ;
        v_status VARCHAR(50);
        v_message TEXT;
    BEGIN
        CALL calculate_rate_delta(
                p_code,
                v_nbk_rate,
                v_xe_rate,
                v_delta,
                v_delta_percent,
                v_last_update_nbk,
                v_last_update_xe,
                v_status,
                v_message
             );

        RETURN QUERY SELECT
                         v_nbk_rate,
                         v_xe_rate,
                         v_delta,
                         v_delta_percent,
                         v_last_update_nbk,
                         v_last_update_xe,
                         v_status,
                         v_message;
    END;
' LANGUAGE plpgsql;