import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const errorRate = new Rate('errors');
const loomResponseTime = new Trend('loom_response_time');
const reactorResponseTime = new Trend('reactor_response_time');

export const options = {
    vus: 5,  // Уменьшим до 5 для smoke теста
    duration: '20s',
    thresholds: {
        'errors': ['rate<0.01'], // Меньше 1% ошибок для smoke
        'loom_response_time': ['p(95)<5000'], // Loom должен быть быстрее
        'reactor_response_time': ['p(95)<3000'], // Reactor еще быстрее
    },
};

// Базовые URL для двух приложений
const LOOM_URL = __ENV.LOOM_URL || 'http://localhost:8081/api';
const REACTOR_URL = __ENV.REACTOR_URL || 'http://localhost:8080/api';

// Валюты для тестирования
const CURRENCIES = ['USD', 'EUR', 'GBP', 'JPY', 'CHF'];

export default function() {
    // 1. ТЕСТ LOOM (синхронный)
    testLoom();

    sleep(0.5); // Небольшая пауза

    // 2. ТЕСТ REACTOR (реактивный)
    testReactor();

    sleep(1);

    // 3. ТЕСТ ДЕЛЬТЫ (оба приложения)
    testDelta();
}

function testLoom() {
    const start = Date.now();

    // Запрос к Loom для сбора курсов
    let res = http.get(`${LOOM_URL}/loom`);
    const duration = Date.now() - start;
    loomResponseTime.add(duration);

    const success = check(res, {
        'loom status is 200': (r) => r.status === 200,
        'loom has data': (r) => {
            try {
                const body = r.json();
                return body && body.nbkCount !== undefined && body.xeCount !== undefined;
            } catch (e) {
                return false;
            }
        },
    });

    if (!success) {
        errorRate.add(1);
        console.log(`❌ Loom error: ${res.status} - ${res.body}`);
    } else {
        const body = res.json();
        console.log(`✅ Loom: NBK=${body.nbkCount}, XE=${body.xeCount}, saved=${body.savedCount}, time=${duration}ms`);
    }
}

function testReactor() {
    const start = Date.now();

    // Запрос к Reactor для сбора курсов
    let res = http.get(`${REACTOR_URL}/rates`);
    const duration = Date.now() - start;
    reactorResponseTime.add(duration);

    const success = check(res, {
        'reactor status is 200': (r) => r.status === 200,
        'reactor has data': (r) => {
            try {
                const body = r.json();
                return body && body.nbkCount !== undefined && body.xeCount !== undefined;
            } catch (e) {
                return false;
            }
        },
    });

    if (!success) {
        errorRate.add(1);
        console.log(`❌ Reactor error: ${res.status}`);
    } else {
        const body = res.json();
        console.log(`✅ Reactor: NBK=${body.nbkCount}, XE=${body.xeCount}, saved=${body.savedCount}, time=${duration}ms`);
    }
}

function testDelta() {
    const currency = CURRENCIES[Math.floor(Math.random() * CURRENCIES.length)];

    // Тест Loom delta
    let loomDelta = http.get(`${LOOM_URL}/loom/${currency}`);
    const loomDeltaSuccess = check(loomDelta, {
        'loom delta status is 200': (r) => r.status === 200,
        'loom delta has data': (r) => {
            try {
                const body = r.json();
                return body && body.code === currency && body.nbkRate && body.xeRate;
            } catch (e) {
                return false;
            }
        },
    });

    if (loomDeltaSuccess) {
        const body = loomDelta.json();
        console.log(`✅ Loom delta ${currency}: NBK=${body.nbkRate}, XE=${body.xeRate}, delta=${body.deltaPercent}%`);
    } else {
        errorRate.add(1);
        console.log(`❌ Loom delta error: ${loomDelta.status}`);
    }

    sleep(0.3);

    // Тест Reactor delta
    let reactorDelta = http.get(`${REACTOR_URL}/rates/${currency}`);
    const reactorDeltaSuccess = check(reactorDelta, {
        'reactor delta status is 200': (r) => r.status === 200,
        'reactor delta has data': (r) => {
            try {
                const body = r.json();
                return body && body.code === currency && body.nbkRate && body.xeRate;
            } catch (e) {
                return false;
            }
        },
    });

    if (reactorDeltaSuccess) {
        const body = reactorDelta.json();
        console.log(`✅ Reactor delta ${currency}: NBK=${body.nbkRate}, XE=${body.xeRate}, delta=${body.deltaPercent}%`);
    } else {
        errorRate.add(1);
        console.log(`❌ Reactor delta error: ${reactorDelta.status}`);
    }
}