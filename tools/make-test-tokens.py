#!/usr/bin/env python3
"""Local issuer only. Credentials go to a caller-selected private file, never stdout."""
import argparse
import json
import os
import urllib.request

parser = argparse.ArgumentParser();
parser.add_argument('--count', type=int, default=1000);
parser.add_argument('--output', required=True);
args = parser.parse_args()
if not 1 <= args.count <= 100000: parser.error('count must be 1..100000')
tokens = []
for i in range(args.count):
    with urllib.request.urlopen('http://127.0.0.1:9000/tokens?subject=load-user-' + str(i),
                                timeout=5) as response: tokens.append(json.load(response)['access_token'])
fd = os.open(args.output, os.O_CREAT | os.O_TRUNC | os.O_WRONLY, 0o600)
with os.fdopen(fd, 'w') as output: json.dump(tokens, output)
print('Wrote', len(tokens), 'signed development identities to the private output file')
