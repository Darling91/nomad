import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const errorRate = new Rate('errors');
const reactorSuccess = new Rate('reactor_success');
const loomSuccess = new Rate('loom_success');

export const options = {
    stages: [
        { duration: '30s', target: 50 },
        { duration: '1m', target: 100 },
        { duration: '1m', target: 200 },
        { duration: '30s', target: 0 },
    ],
    thresholds: {
        http_req_duration: ['p(95)<35000'],

        'http_req_duration{service:reactor}': ['p(95)<40000'],
        'http_req_duration{service:loom}': ['p(95)<25000'],

        'http_req_failed{service:reactor}': ['rate<0.35'],
        'http_req_failed{service:loom}': ['rate<0.05'],

    },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api';

export default function() {
    const vuCount = __VU;

    let timeout = '25s';
    if (vuCount > 150) {
        timeout = '20s';
    } else if (vuCount < 50) {
        timeout = '30s';
    }

    const responses = http.batch([
        {
            method: 'GET',
            url: `${BASE_URL}/rates`,
            timeout: timeout,
            tags: { service: 'reactor' },
        },
        {
            method: 'GET',
            url: `${BASE_URL}/loom`,
            timeout: timeout,
            tags: { service: 'loom' },
        },
    ]);

    const reactorOk = check(responses[0], {
        'reactor sync ok': (r) => r.status === 200,
    });

    const loomOk = check(responses[1], {
        'loom sync ok': (r) => r.status === 200,
    });

    if (vuCount > 150) {
        if (!reactorOk && responses[0].status === 503) {
            console.log(`⚠️ Reactor 503 at VU ${vuCount}`);
        }
        if (!loomOk && responses[1].status === 503) {
            console.log(`⚠️ Loom 503 at VU ${vuCount}`);
        }
    }

    if (!reactorOk && !loomOk) {
        errorRate.add(1);
    }

    if (vuCount > 150) {
        sleep(2);
    } else if (vuCount > 100) {
        sleep(1.5);
    } else {
        sleep(1);
    }
}