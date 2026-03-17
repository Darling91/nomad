-- liquibase formatted sql

-- changeset nomad:2-3
-- Процедура расчёта дельты
CREATE OR REPLACE PROCEDURE calculate_rate_delta(
    IN p_code VARCHAR(3),
    OUT nbk_rate DECIMAL(20,6),
    OUT xe_rate DECIMAL(20,6),
    OUT delta DECIMAL(20,6),
    OUT delta_percent DECIMAL(10,4),
    OUT last_update_nbk TIMESTAMPTZ,
    OUT last_update_xe TIMESTAMPTZ,
    OUT status VARCHAR(50),
    OUT message TEXT
)
    LANGUAGE plpgsql
AS '
    DECLARE
        v_nbk_exists BOOLEAN;
        v_xe_exists BOOLEAN;
    BEGIN
        -- Проверяем наличие курсов
        SELECT EXISTS(SELECT 1 FROM currency_rates WHERE code = p_code AND source_id = ''NBK'') INTO v_nbk_exists;
        SELECT EXISTS(SELECT 1 FROM currency_rates WHERE code = p_code AND source_id = ''XE'') INTO v_xe_exists;

        -- Получаем курс NBK
        SELECT rate, updated_at INTO nbk_rate, last_update_nbk
        FROM currency_rates
        WHERE code = p_code AND source_id = ''NBK''
            FOR UPDATE;

        -- Получаем курс XE
        SELECT rate, updated_at INTO xe_rate, last_update_xe
        FROM currency_rates
        WHERE code = p_code AND source_id = ''XE''
            FOR UPDATE;

        -- Формируем статус
        IF nbk_rate IS NULL AND xe_rate IS NULL THEN
            status := ''ERROR'';
            message := ''Курсы для '' || p_code || '' не найдены ни в одном источнике'';
            delta := NULL;
            delta_percent := NULL;
        ELSIF nbk_rate IS NULL THEN
            status := ''PARTIAL'';
            message := ''Отсутствует курс NBK для '' || p_code;
            delta := NULL;
            delta_percent := NULL;
        ELSIF xe_rate IS NULL THEN
            status := ''PARTIAL'';
            message := ''Отсутствует курс XE для '' || p_code;
            delta := NULL;
            delta_percent := NULL;
        ELSE
            -- Вычисляем дельты
            delta := nbk_rate - xe_rate;
            delta_percent := (delta / xe_rate) * 100;
            status := ''OK'';
            message := ''Расчёт выполнен успешно'';
        END IF;
    END;
';