import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const errorRate = new Rate('errors');
const deadlockRate = new Rate('deadlocks');

const syncDuration = new Trend('sync_duration');
const deltaDuration = new Trend('delta_duration');

export const options = {
    scenarios: {
        // Тест только для Loom (синхронный)
        loom_read_test: {
            executor: 'constant-vus',
            vus: 10,
            duration: '1m',
            exec: 'loomReadTest',
            startTime: '0s',
        },
        // Тест только для Reactor (реактивный)
        reactor_read_test: {
            executor: 'constant-vus',
            vus: 10,
            duration: '1m',
            exec: 'reactorReadTest',
            startTime: '0s',
        },
        // Запись только через Loom (коллектор)
        write_test: {
            executor: 'constant-vus',
            vus: 3,
            duration: '1m',
            exec: 'writeOnlyTest',
            startTime: '30s',
        },
        // Смешанный тест для Loom
        loom_mixed_test: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 5 },
                { duration: '1m', target: 5 },
                { duration: '30s', target: 0 },
            ],
            exec: 'loomMixedTest',
            startTime: '90s',
        },
        // Смешанный тест для Reactor
        reactor_mixed_test: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 5 },
                { duration: '1m', target: 5 },
                { duration: '30s', target: 0 },
            ],
            exec: 'reactorMixedTest',
            startTime: '90s',
        },
    },
    thresholds: {
        'delta_duration': ['p(95)<2500'],      // Для чтения дельты
        'sync_duration': ['p(95)<25000'],      // Для синхронной записи
        'reactor_duration': ['p(95)<2000'],    // Для реактивного чтения
        'deadlocks': ['rate<0.002'],           // Дедлоки < 0.2%
    },
};

// Базовые URL для двух приложений
const LOOM_URL = __ENV.LOOM_URL || 'http://localhost:8081/api';
const REACTOR_URL = __ENV.REACTOR_URL || 'http://localhost:8080/api';
const CURRENCIES = ['USD', 'EUR', 'GBP', 'JPY', 'CHF', 'CAD', 'AUD', 'CNY'];

// ============================================
// ТЕСТЫ ДЛЯ LOOM (СИНХРОННЫЙ)
// ============================================

// Чтение дельты через Loom
export function loomReadTest() {
    const currency = CURRENCIES[Math.floor(Math.random() * CURRENCIES.length)];

    const start = Date.now();
    const res = http.get(`${LOOM_URL}/loom/${currency}`);
    const duration = Date.now() - start;

    deltaDuration.add(duration);

    if (res.status >= 500) {
        errorRate.add(1);
        console.log(`❌ Loom read error ${res.status} for ${currency}`);
    } else {
        check(res, {
            'loom read status 200': (r) => r.status === 200,
            'loom read has data': (r) => {
                try {
                    const body = r.json();
                    return body && body.code === currency;
                } catch (e) {
                    return false;
                }
            },
        });
    }

    sleep(0.2);
}

// Запись (сбор курсов) только через Loom
export function writeOnlyTest() {
    const start = Date.now();
    const res = http.get(`${LOOM_URL}/loom`);
    const duration = Date.now() - start;

    syncDuration.add(duration);

    if (res.status >= 500) {
        errorRate.add(1);
        console.log(`❌ Write error ${res.status}`);
    } else {
        check(res, {
            'write status 200': (r) => r.status === 200,
            'write saved data': (r) => {
                try {
                    const body = r.json();
                    return body && body.savedCount > 0;
                } catch (e) {
                    return false;
                }
            },
        });
    }

    // Проверка на дедлок (>35 секунд)
    if (duration > 35000) {
        deadlockRate.add(1);
        console.log(`⚠️ Дедлок в write: ${duration}ms`);
    } else if (duration > 25000) {
        console.log(`⚠️ Долгий запрос write: ${duration}ms`);
    }

    sleep(3);
}

// Смешанный тест для Loom (90% чтение, 10% запись)
export function loomMixedTest() {
    const vuId = __VU;
    const currency = CURRENCIES[vuId % CURRENCIES.length];

    const isRead = Math.random() < 0.9;
    const start = Date.now();

    if (isRead) {
        const res = http.get(`${LOOM_URL}/loom/${currency}`);
        const duration = Date.now() - start;

        deltaDuration.add(duration);

        if (res.status >= 500) {
            errorRate.add(1);
            console.log(`❌ Loom mixed read error ${res.status} for ${currency}`);
        }

        sleep(Math.random() * 0.3);

    } else {
        const res = http.get(`${LOOM_URL}/loom`);
        const duration = Date.now() - start;

        syncDuration.add(duration);

        if (res.status >= 500) {
            errorRate.add(1);
            console.log(`❌ Loom mixed write error ${res.status}`);
        }

        if (duration > 35000) {
            deadlockRate.add(1);
            console.log(`⚠️ Дедлок в loom mixed write: ${duration}ms`);
        } else if (duration > 25000) {
            console.log(`⚠️ Долгий loom mixed write: ${duration}ms`);
        }

        sleep(2 + Math.random());
    }
}

// ============================================
// ТЕСТЫ ДЛЯ REACTOR (РЕАКТИВНЫЙ)
// ============================================

const reactorDuration = new Trend('reactor_duration');

// Чтение дельты через Reactor
export function reactorReadTest() {
    const currency = CURRENCIES[Math.floor(Math.random() * CURRENCIES.length)];

    const start = Date.now();
    const res = http.get(`${REACTOR_URL}/rates/${currency}`);
    const duration = Date.now() - start;

    reactorDuration.add(duration);

    if (res.status >= 500) {
        errorRate.add(1);
        console.log(`❌ Reactor read error ${res.status} for ${currency}`);
    } else {
        check(res, {
            'reactor read status 200': (r) => r.status === 200,
            'reactor read has data': (r) => {
                try {
                    const body = r.json();
                    return body && body.code === currency;
                } catch (e) {
                    return false;
                }
            },
        });
    }

    sleep(0.1); // Реактивный быстрее
}

// Смешанный тест для Reactor (только чтение, запись только через Loom)
export function reactorMixedTest() {
    const currency = CURRENCIES[__VU % CURRENCIES.length];

    const start = Date.now();
    const res = http.get(`${REACTOR_URL}/rates/${currency}`);
    const duration = Date.now() - start;

    reactorDuration.add(duration);

    if (res.status >= 500) {
        errorRate.add(1);
        console.log(`❌ Reactor mixed read error ${res.status} for ${currency}`);
    }

    sleep(Math.random() * 0.2);
}