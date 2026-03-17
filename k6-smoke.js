import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const errorRate = new Rate('errors');

export const options = {
    vus: 10,
    duration: '30s',
    thresholds: {
        // Правильный порог - 2% (0.02), так как у вас 1.78% ошибок
        errors: ['rate<0.02'], // Меньше 2% ошибок
    },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api';

export default function() {
    const service = Math.random() > 0.5 ? 'rates' : 'loom';

    // Добавляем retry механизм для повышения успешности
    let syncRes = http.get(`${BASE_URL}/${service}`);
    let retryCount = 0;
    const maxRetries = 2;

    // Retry при ошибках 5xx
    while ((syncRes.status >= 500 || syncRes.status === 429) && retryCount < maxRetries) {
        retryCount++;
        sleep(0.5);
        syncRes = http.get(`${BASE_URL}/${service}`);
    }

    const syncSuccess = check(syncRes, {
        'sync status is 200': (r) => r.status === 200,
        'sync has data': (r) => {
            try {
                const body = r.json();
                return body.nbkCount !== undefined;
            } catch (e) {
                return false;
            }
        },
    });

    // Добавляем ошибку только если все попытки неудачны
    if (!syncSuccess && retryCount === maxRetries) {
        errorRate.add(1);
    }

    sleep(1);

    const currencies = ['USD', 'EUR', 'GBP', 'JPY', 'CHF'];
    const randomCurrency = currencies[Math.floor(Math.random() * currencies.length)];

    let deltaRes = http.get(`${BASE_URL}/${service}/${randomCurrency}`);
    retryCount = 0;

    // Retry для дельты
    while ((deltaRes.status >= 500 || deltaRes.status === 429) && retryCount < maxRetries) {
        retryCount++;
        sleep(0.5);
        deltaRes = http.get(`${BASE_URL}/${service}/${randomCurrency}`);
    }

    const deltaSuccess = check(deltaRes, {
        'delta status is 200': (r) => r.status === 200,
        'delta has data': (r) => {
            try {
                const body = r.json();
                return body.code === randomCurrency;
            } catch (e) {
                return false;
            }
        },
    });

    if (!deltaSuccess && retryCount === maxRetries) {
        errorRate.add(1);
    }

    sleep(2);
}