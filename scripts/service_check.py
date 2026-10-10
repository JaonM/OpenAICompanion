#!/usr/bin/env python3
"""Read-only readiness, disk and interval-error checks for an external monitor."""
import argparse
import json
import os
from pathlib import Path
import shutil
import tempfile
import urllib.request
from urllib.parse import urlsplit


def error_delta(previous, current):
    # Counter reset/restart starts a new baseline, not a negative interval.
    if sum(current.values()) < sum(previous.values()):
        return 0, 0
    delta = {key: max(0, value - previous.get(key, 0)) for key, value in current.items()}
    return sum(value for key, value in delta.items() if 500 <= int(key.rsplit(':', 1)[1]) < 600), sum(delta.values())


def read_json(url, token=None):
    request = urllib.request.Request(url, headers={'Authorization': 'Bearer ' + token} if token else {})
    with urllib.request.urlopen(request, timeout=5) as response:
        payload = response.read(1024 * 1024 + 1)
    if len(payload) > 1024 * 1024:
        raise ValueError('probe response exceeds capacity')
    return json.loads(payload)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', required=True)
    parser.add_argument('--data', help='Local filesystem path of the service data volume')
    parser.add_argument('--state', help='Private counter baseline file for interval 5xx checks')
    parser.add_argument('--min-free-bytes', type=int, default=1_000_000_000)
    parser.add_argument('--min-requests', type=int, default=20)
    parser.add_argument('--max-error-rate', type=float, default=0.05)
    args = parser.parse_args()
    parsed = urlsplit(args.url)
    if (not parsed.netloc or parsed.username or parsed.password or parsed.query or parsed.fragment
            or parsed.path not in ('', '/') or not (parsed.scheme == 'https' or
                parsed.scheme == 'http' and parsed.hostname in ('localhost', '127.0.0.1', '::1'))):
        parser.error('--url must be an HTTPS origin (loopback HTTP allowed)')
    if args.min_free_bytes < 0 or args.min_requests < 1 or not 0 <= args.max_error_rate <= 1:
        parser.error('invalid check thresholds')
    checks = {}
    try:
        checks['ready'] = read_json(args.url.rstrip('/') + '/readyz').get('ok') is True
    except Exception:
        checks['ready'] = False
    if args.data:
        try:
            checks['disk'] = shutil.disk_usage(args.data).free >= args.min_free_bytes
        except OSError:
            checks['disk'] = False
    token = os.environ.get('COMPANION_METRICS_TOKEN')
    if token:
        try:
            metrics = read_json(args.url.rstrip('/') + '/metrics', token)
            counters = {f"{item['route']}:{int(item['status'])}": int(item['count']) for item in metrics['requests']}
            checks['metrics'] = True
            if args.state:
                state = Path(args.state)
                previous = json.loads(state.read_text()) if state.exists() else None
                if previous is not None:
                    errors, total = error_delta(previous, counters)
                    checks['interval_errors'] = total < args.min_requests or errors / total <= args.max_error_rate
                state.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                descriptor, temporary = tempfile.mkstemp(dir=state.parent, prefix='.probe-')
                try:
                    with os.fdopen(descriptor, 'w') as output:
                        json.dump(counters, output)
                    os.replace(temporary, state)
                finally:
                    if os.path.exists(temporary):
                        os.unlink(temporary)
        except Exception:
            checks['metrics'] = False
    elif args.state:
        checks['metrics'] = False
    print(json.dumps({'ok': all(checks.values()), 'checks': checks}))
    return 0 if all(checks.values()) else 1


if __name__ == '__main__':
    raise SystemExit(main())
