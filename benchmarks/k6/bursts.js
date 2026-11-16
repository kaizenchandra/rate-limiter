import http from 'k6/http';

const tokens = JSON.parse(open(__ENV.TOKENS_FILE || '../tokens.example.json'));
export const options = {
    scenarios: {
        bursts: {
            executor: 'ramping-arrival-rate',
            startRate: 1000,
            timeUnit: '1s',
            preAllocatedVUs: 200,
            maxVUs: 1000,
            stages: [{target: 1000, duration: '20s'}, {target: 10000, duration: '1s'}, {
                target: 10000,
                duration: '5s'
            }, {target: 1000, duration: '1s'}, {target: 1000, duration: '20s'}]
        }
    }
};
export default function () {
    http.get(`${__ENV.BASE_URL || 'http://127.0.0.1:8080'}/protected/demo`, {
        headers: {Authorization: `Bearer ${tokens[Math.floor(Math.random() * tokens.length)]}`},
        timeout: '2s'
    });
}
