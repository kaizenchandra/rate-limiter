import http from 'k6/http';
import {Counter, Trend} from 'k6/metrics';
import {check} from 'k6';

const allowed = new Counter('limiter_allowed');
const denied = new Counter('limiter_denied');
const errors = new Counter('limiter_errors');
const latency = new Trend('decision_http_latency', true);
const rate = Number(__ENV.RATE || 10000);
const tokens = JSON.parse(open(__ENV.TOKENS_FILE || '../tokens.example.json'));
const distribution = __ENV.DISTRIBUTION || 'uniform';
export const options = {
    summaryTrendStats: ["avg", "min", "med", "max", "p(95)", "p(99)"],
    discardResponseBodies: false,
    scenarios: {
        decisions: {
            executor: 'constant-arrival-rate',
            rate,
            timeUnit: '1s',
            duration: __ENV.DURATION || '60s',
            preAllocatedVUs: Number(__ENV.VUS || 200),
            maxVUs: Number(__ENV.MAX_VUS || 1000)
        }
    },
    thresholds: {limiter_errors: ['count==0'], dropped_iterations: ['count==0']}
};

function identityIndex() {
    if (distribution === 'hot') return Math.random() < .8 ? 0 : Math.floor(Math.random() * tokens.length);
    if (distribution === 'zipf') { // exact truncated Zipf CDF, prepared once in init context below
        let u = Math.random(), lo = 0, hi = cdf.length - 1;
        while (lo < hi) {
            const m = (lo + hi) >> 1;
            if (cdf[m] >= u) hi = m; else lo = m + 1;
        }
        return lo;
    }
    return Math.floor(Math.random() * tokens.length);
}

const weights = tokens.map((_, i) => 1 / Math.pow(i + 1, 1.1));
const total = weights.reduce((a, b) => a + b, 0);
let cumulative = 0;
const cdf = weights.map(w => (cumulative += w / total));
export default function () {
    const index = identityIndex();
    const result = http.post(`${__ENV.BASE_URL || 'http://127.0.0.1:8080'}/api/v1/rate-limits/evaluate`, JSON.stringify({
        endpoint: __ENV.ENDPOINT || '/protected/demo',
        cost: 1
    }), {
        headers: {Authorization: `Bearer ${tokens[index]}`, 'Content-Type': 'application/json'},
        timeout: '2s',
        tags: {name: 'evaluate'}
    });
    latency.add(result.timings.duration);
    if (result.status === 200) allowed.add(1); else if (result.status === 429) denied.add(1); else errors.add(1);
    check(result, {'recognized decision': r => r.status === 200 || r.status === 429});
}

export function handleSummary(data) {
    return {
        [__ENV.RESULT || 'summary.json']: JSON.stringify({
            scope: 'HTTP decision endpoint including auth and network; not engine-only latency',
            distribution,
            offeredRate: rate, ...data
        }, null, 2)
    };
}
