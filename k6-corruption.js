import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const errorRate = new Rate('errors');
const deadlockRate = new Rate('deadlocks');

const syncDuration = new Trend('sync_duration');
const deltaDuration = new Trend('delta_duration');

export const options = {
    scenarios: {
        read_test: {
            executor: 'constant-vus',
            vus: 20,
            duration: '1m',
            exec: 'readOnlyTest',
            startTime: '0s',
        },
        write_test: {
            executor: 'constant-vus',
            vus: 3,
            duration: '1m',
            exec: 'writeOnlyTest',
            startTime: '30s',
        },
        mixed_test: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 10 },
                { duration: '1m', target: 10 },
                { duration: '30s', target: 0 },
            ],
            exec: 'mixedTest',
            startTime: '90s',
        },
    },
    thresholds: {
        'delta_duration': ['p(95)<2500'],

        'sync_duration': ['p(95)<25000'],

        deadlocks: ['rate<0.002'],

    },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api';
const CURRENCIES = ['USD', 'EUR', 'GBP', 'JPY', 'CHF', 'CAD', 'AUD', 'CNY'];

export function readOnlyTest() {
    const currency = CURRENCIES[Math.floor(Math.random() * CURRENCIES.length)];

    const service = Math.random() > 0.3 ? 'loom' : 'rates';

    const start = Date.now();
    const res = http.get(`${BASE_URL}/${service}/${currency}`);
    const duration = Date.now() - start;

    deltaDuration.add(duration);

    if (res.status >= 500) {
        errorRate.add(1);
        console.log(`❌ Read error ${res.status} for ${currency} (${service})`);
    } else {
        check(res, {
            'read status 200': (r) => r.status === 200,
            'read has data': (r) => {
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

export function writeOnlyTest() {
    const start = Date.now();
    const res = http.get(`${BASE_URL}/loom`);
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

    if (duration > 35000) {
        deadlockRate.add(1);
        console.log(`⚠️ Дедлок в write: ${duration}ms`);
    } else if (duration > 25000) {
        console.log(`⚠️ Долгий запрос: ${duration}ms`);
    }

    sleep(3);
}


export function mixedTest() {
    const vuId = __VU;
    const currency = CURRENCIES[vuId % CURRENCIES.length];

    const isRead = Math.random() < 0.9;

    const start = Date.now();

    if (isRead) {
        const service = Math.random() > 0.4 ? 'loom' : 'rates';
        const res = http.get(`${BASE_URL}/${service}/${currency}`);
        const duration = Date.now() - start;

        deltaDuration.add(duration);

        if (res.status >= 500) {
            errorRate.add(1);
            console.log(`❌ Mixed read error ${res.status} for ${currency}`);
        }

        sleep(Math.random() * 0.3);

    } else {
        const res = http.get(`${BASE_URL}/loom`);
        const duration = Date.now() - start;

        syncDuration.add(duration);

        if (res.status >= 500) {
            errorRate.add(1);
            console.log(`❌ Mixed write error ${res.status}`);
        }

        // Проверка на реальный дедлок (>35 секунд)
        if (duration > 35000) {
            deadlockRate.add(1);
            console.log(`⚠️ Дедлок в mixed write: ${duration}ms`);
        } else if (duration > 25000) {
            console.log(`⚠️ Долгий mixed write: ${duration}ms`);
        }

        sleep(2 + Math.random());
    }
}