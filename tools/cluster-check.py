#!/usr/bin/env python3
"""Disposable six-node Redis Cluster checks. Never point at a production topology."""
import json
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COMPOSE = ['docker', 'compose', '-f', str(ROOT / 'docker/cluster/docker-compose.yml')]


def run(*args, check=True):
    return subprocess.run(args, check=check, capture_output=True, text=True).stdout.strip()


def container(name): return run(*COMPOSE, 'ps', '-q', name)


def cli(name, *args): return run('docker', 'exec', container(name), 'redis-cli', '--raw', *args)


run(*COMPOSE, 'up', '-d')
for attempt in range(60):
    try:
        if 'cluster_state:ok' in cli('redis-0', 'cluster', 'info'): break
    except subprocess.CalledProcessError:
        pass
    time.sleep(.5)
else:
    raise RuntimeError('Cluster failed to initialize; inspect init service logs')
run('docker', 'cp', str(ROOT / 'src/main/resources/redis/consume.lua'), container('redis-0') + ':/tmp/consume.lua')
# Unique domain makes repeat runs independent; full cap consumed, refill negligible in this drill.
domain = 'cluster-' + str(time.time_ns());
prefix = 'rl:{' + domain + '}:'


def evaluate(cost):
    return cli('redis-0', '-c', '--eval', '/tmp/consume.lua', prefix + 'revision', prefix + 'identities',
               prefix + 'user', ',', '1', str(cost), '100000', '10', '.001', 'consume').splitlines()


assert evaluate(10) == ['1', '0', '0'], 'Initial all-token allocation failed'
assert evaluate(1)[0] == '0', 'Exhausted bucket allowed'
slot = int(cli('redis-0', 'cluster', 'keyslot', prefix + 'user'))
nodes = cli('redis-0', 'cluster', 'nodes').splitlines();
owner = None
for node in nodes:
    fields = node.split()
    if 'master' not in fields[2]: continue
    for part in fields[8:]:
        if part.startswith('['): continue
        bounds = part.split('-');
        low = int(bounds[0]);
        high = int(bounds[-1])
        if low <= slot <= high: owner = fields[0]
assert owner
replica = next(line.split()[0] for line in nodes if line.split()[3] == owner)
by_id = {cli('redis-' + str(i), 'cluster', 'myid'): 'redis-' + str(i) for i in range(6)}
replica_name = by_id[replica]
# Wait until the replica actually has this deduction, then perform controlled promotion.
for _ in range(60):
    if subprocess.run(['docker', 'exec', '-i', container(replica_name), 'redis-cli', '--raw'],
                      input='READONLY\nHGET ' + prefix + 'user tokens\n', text=True, capture_output=True,
                      check=True).stdout.strip().splitlines()[-1] not in ('OK', ''):
        values = subprocess.run(['docker', 'exec', '-i', container(replica_name), 'redis-cli', '--raw'],
                                input='READONLY\nHGET ' + prefix + 'user tokens\n', text=True, capture_output=True,
                                check=True).stdout.strip().splitlines()
        if float(values[-1]) < 1: break
    time.sleep(.1)
else:
    raise RuntimeError('Replica did not receive deduction')
assert cli(replica_name, 'cluster', 'failover') == 'OK'
for _ in range(100):
    if 'role:master' in cli(replica_name, 'info', 'replication'): break
    time.sleep(.1)
else:
    raise RuntimeError('Promotion timed out')
# Await cluster convergence without retrying an allowed deduction; reads and known quota rejections only.
for _ in range(100):
    out = evaluate(1)
    if out and out[0] == '1': raise RuntimeError('Promotion admitted from an exhausted bucket; quota state was lost')
    if out and out[0] == '0': break
    time.sleep(.1)
else:
    raise RuntimeError('New primary failed to preserve exhausted balance: ' + str(out))
cross = cli('redis-0', 'eval', 'return 1', '2', '{first}:a', '{second}:b')
assert 'CROSSSLOT' in cross, cross
result = {'sameSlotAtomicScript': 'passed', 'crossSlotRejected': 'passed',
          'controlledCaughtUpReplicaPromotion': 'passed', 'abruptRollbackBound': 'not tested', 'slot': slot,
          'redisVersion': '8.2.11'}
(ROOT / 'benchmarks/results').mkdir(parents=True, exist_ok=True)
(ROOT / 'benchmarks/results/cluster-check.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2))
