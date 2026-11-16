// k6-side algorithm comparison only. This does not call the Java engine or enforce across VUs.
// Each VU owns its state: deliberately NEVER use it as distributed correctness evidence.
import {Counter} from 'k6/metrics';

const accepted = new Counter('local_allowed');
const rejected = new Counter('local_rejected');
export const options = {vus: 1, duration: '10s'};
let balance = 1000, last = Date.now();
export default function () {
    const now = Date.now();
    balance = Math.min(1000, balance + (now - last));
    last = now;
    if (balance >= 1) {
        balance--;
        accepted.add(1);
    } else rejected.add(1);
}
