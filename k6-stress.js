import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

const errorRate = new Rate('errors');
const reactorSuccess = new Rate('reactor_success');
const loomSuccess = new Rate('loom_success');
const externalApiErrors = new Rate('external_api_errors');
const dbErrors = new Rate('db_errors');

// Счетчики RPS
const reactorRPS = new Counter('reactor_rps');
const loomRPS = new Counter('loom_rps');
const totalRPS = new Counter('total_rps');

export const options = {
    scenarios: {
        // ЧЕСТНЫЙ профиль нагрузки
        honest_load: {
            executor: 'ramping-arrival-rate',
            startRate: 10,           // Начинаем с 10 RPS
            timeUnit: '1s',
            preAllocatedVUs: 20,      // Реально: 20 VUs готовы
            maxVUs: 200,               // Реально: максимум 200 VUs (не 300!)
            stages: [
                { target: 50, duration: '30s' },   // 0:00 → 0:30: 10 → 50 RPS
                { target: 100, duration: '30s' },  // 0:30 → 1:00: 50 → 100 RPS
                { target: 200, duration: '30s' },  // 1:00 → 1:30: 100 → 200 RPS
                { target: 200, duration: '30s' },  // 1:30 → 2:00: 200 RPS (пик)
                { target: 0, duration: '30s' },    // 2:00 → 2:30: 200 → 0 RPS
            ],
        },
    },
    thresholds: {
        // Реалистичные пороги
           // ~200 RPS * 90 сек активной нагрузки

        'http_req_duration{type:sync}': ['p(95)<50000'],
        'http_req_duration{type:delta}': ['p(95)<6000'],

        'external_api_errors': ['rate<0.35'],
        'db_errors': ['rate<0.05'],
    },
};

const LOOM_URL = __ENV.LOOM_URL || 'http://localhost:8081/api';
const REACTOR_URL = __ENV.REACTOR_URL || 'http://localhost:8080/api';
const CURRENCIES = ['USD', 'EUR', 'GBP', 'JPY', 'CHF', 'CAD', 'AUD', 'CNY', 'RUB', 'TRY'];

// ЧЕСТНОЕ определение этапов
const STAGES = {
    '0-30': { name: 'Разогрев до 50 RPS', timeout: '50s' },
    '30-60': { name: 'Рост до 100 RPS', timeout: '48s' },
    '60-90': { name: 'Рост до 200 RPS', timeout: '45s' },
    '90-120': { name: 'Пик 200 RPS', timeout: '40s' },
    '120-150': { name: 'Спад', timeout: '50s' },
};

export default function() {
    // ЧЕСТНОЕ определение текущего этапа по времени (грубо)
    const timeSec = (Date.now() - __VU * 1000) / 1000; // не идеально, но для примера
    let stage, timeout, deltaRatio;

    if (timeSec < 30) {
        stage = STAGES['0-30'];
        deltaRatio = 0.2;
    } else if (timeSec < 60) {
        stage = STAGES['30-60'];
        deltaRatio = 0.25;
    } else if (timeSec < 90) {
        stage = STAGES['60-90'];
        deltaRatio = 0.3;
    } else if (timeSec < 120) {
        stage = STAGES['90-120'];
        deltaRatio = 0.2; // На пике меньше дельт
    } else {
        stage = STAGES['120-150'];
        deltaRatio = 0.15;
    }

    const rnd = Math.random();

    if (rnd < deltaRatio) {
        handleDeltaRequest(stage.name);
    } else {
        // 60% запросов на Reactor, 40% на Loom
        const useReactor = Math.random() < 0.6;
        const service = useReactor ? 'reactor' : 'loom';
        const baseUrl = useReactor ? REACTOR_URL : LOOM_URL;
        const endpoint = useReactor ? '/rates' : '/loom';

        handleSyncRequest(baseUrl, endpoint, service, stage.timeout, stage.name);
    }

    // ЧЕСТНАЯ пауза (не зависит от нагрузки)
    sleep(0.2);
}

function handleSyncRequest(baseUrl, endpoint, service, timeout, stageName) {
    const fullUrl = `${baseUrl}${endpoint}`;

    const res = http.get(fullUrl, {
        timeout: timeout,
        tags: {
            service: service,
            type: 'sync',
            stage: stageName,
        },
    });

    // Обновляем счетчики
    if (service === 'reactor') {
        reactorRPS.add(1);
    } else {
        loomRPS.add(1);
    }
    totalRPS.add(1);

    if (res.status === 200) {
        try {
            const body = res.json();
            if (body && body.nbkCount !== undefined) {
                if (service === 'reactor') {
                    reactorSuccess.add(1);
                } else {
                    loomSuccess.add(1);
                }

                // Логируем каждый 50-й успешный запрос
                if (__ITER % 50 === 0) {
                    console.log(`✅ ${service} [${stageName}]: ${body.nbkCount + body.xeCount} курсов`);
                }
                return;
            }
        } catch (e) {
            // Ошибка парсинга
        }
    }

    // Анализ ошибок
    if (res.status === 502 || res.status === 504) {
        externalApiErrors.add(1);
        if (__ITER % 20 === 0) {
            console.log(`🌐 ${service} timeout [${stageName}]`);
        }
    } else if (res.status === 409 || (res.status === 500 && res.body?.includes('deadlock'))) {
        dbErrors.add(1);
        console.log(`💾 ${service} deadlock [${stageName}]`);
    } else if (res.status >= 500) {
        if (__ITER % 20 === 0) {
            console.log(`❌ ${service} error ${res.status} [${stageName}]`);
        }
    }

    errorRate.add(1);
}

function handleDeltaRequest(stageName) {
    const currency = CURRENCIES[Math.floor(Math.random() * CURRENCIES.length)];

    // Поочередно тестируем оба сервиса
    const services = [
        { url: REACTOR_URL, endpoint: '/rates', name: 'reactor' },
        { url: LOOM_URL, endpoint: '/loom', name: 'loom' },
    ];

    services.forEach(service => {
        const res = http.get(`${service.url}${service.endpoint}/${currency}`, {
            timeout: '6s',
            tags: {
                service: service.name,
                type: 'delta',
                stage: stageName,
            },
        });
    });
}