#!/usr/bin/env python3
"""Bounded ASGI entry point for the memory relay and personal-device gateway."""
import asyncio
from collections import Counter, OrderedDict
import hmac
import json
import logging
import os
from pathlib import Path
import re
import sqlite3
import time
from urllib.parse import urlsplit
import uuid

from device_task_server import TaskStore, RpcError, TERMINAL, INTERRUPTED, encode
from memory_sync_server import RelayStore, CapacityError, MAX_BODY
from service_credentials import Credentials

LOG = logging.getLogger('companion.service')


class HttpError(Exception):
    def __init__(self, status, detail):
        self.status, self.detail = status, detail


class RateLimiter:
    def __init__(self, limit=600, capacity=4096):
        self.limit, self.capacity, self.entries = limit, capacity, OrderedDict()

    def allow(self, key):
        now = time.monotonic()
        start, count = self.entries.pop(key, (now, 0))
        if now - start >= 60:
            start, count = now, 0
        self.entries[key] = (start, count + 1)
        while len(self.entries) > self.capacity:
            self.entries.popitem(last=False)
        return count < self.limit


class CompanionService:
    def __init__(self, directory, base_url, metrics_token=None):
        parsed = urlsplit(base_url)
        if (parsed.scheme != 'https' and not (parsed.scheme == 'http' and parsed.hostname in ('127.0.0.1', 'localhost'))
                or not parsed.netloc or parsed.username or parsed.password or parsed.path not in ('', '/')
                or parsed.query or parsed.fragment):
            raise ValueError('base URL must be an HTTPS origin (loopback HTTP allowed for development)')
        directory = Path(directory)
        directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.credentials = Credentials(str(directory / 'credentials.sqlite'))
        self.tasks = TaskStore(str(directory / 'device-tasks.sqlite'))
        self.memories = RelayStore(str(directory / 'memory-sync.sqlite'))
        self.base_url = base_url.rstrip('/')
        self.metrics_token = metrics_token
        self.rates, self.pairing_rates = RateLimiter(), RateLimiter(30)
        self.counts, self.duration_ms = Counter(), Counter()

    async def __call__(self, scope, receive, send):
        if scope['type'] == 'lifespan':
            while True:
                event = await receive()
                if event['type'] == 'lifespan.startup':
                    await send({'type': 'lifespan.startup.complete'})
                elif event['type'] == 'lifespan.shutdown':
                    await send({'type': 'lifespan.shutdown.complete'})
                    return
        if scope['type'] != 'http':
            return
        started, request_id, route = time.monotonic(), uuid.uuid4().hex, 'unknown'
        headers = {key.decode().lower(): value.decode() for key, value in scope['headers']}
        status, payload, extras = 500, {'error': 'internal error'}, []
        try:
            route, operation = self.route(scope['method'], scope['path'])
            payload, status = await asyncio.wait_for(operation(scope, headers, receive), timeout=30)
        except HttpError as error:
            status, payload = error.status, {'error': error.detail}
        except CapacityError:
            status, payload = 413, {'error': 'request or response exceeds capacity'}
        except (ValueError, TypeError, KeyError, AttributeError, UnicodeError):
            status, payload = 400, {'error': 'invalid request'}
        except (TimeoutError, asyncio.TimeoutError):
            status, payload = 504, {'error': 'request timed out; do not replay mutations'}
        except sqlite3.Error:
            status, payload = 503, {'error': 'storage unavailable'}
        except Exception:
            # Exception messages can contain SQL arguments or personal content.
            status, payload = 500, {'error': 'internal error'}
        elapsed = round((time.monotonic() - started) * 1000)
        self.counts[route, status] += 1
        self.duration_ms[route] += elapsed
        LOG.info(json.dumps({'request_id': request_id, 'route': route, 'status': status, 'elapsed_ms': elapsed}))
        if status == 401:
            extras.append((b'www-authenticate', b'Bearer'))
        if status in (429, 503):
            extras.append((b'retry-after', b'60'))
        body = encode(payload)
        await send({'type': 'http.response.start', 'status': status, 'headers': [
            (b'content-type', b'application/json'), (b'content-length', str(len(body)).encode()),
            (b'cache-control', b'no-store'), (b'x-content-type-options', b'nosniff'),
            (b'x-request-id', request_id.encode()), *extras]})
        await send({'type': 'http.response.body', 'body': body})

    async def identity(self, headers, scope=None):
        authorization = headers.get('authorization', '')
        token = authorization[7:] if authorization.startswith('Bearer ') else ''
        identity = await asyncio.to_thread(self.credentials.authenticate, token)
        if identity is None:
            raise HttpError(401, 'authentication required')
        if scope and scope not in identity['scopes']:
            raise HttpError(403, 'credential scope denied')
        if not self.rates.allow(identity['id']):
            raise HttpError(429, 'request rate exceeded')
        return identity

    async def body(self, headers, receive, maximum=MAX_BODY):
        if headers.get('content-type', '').split(';')[0].lower() != 'application/json':
            raise HttpError(415, 'application/json required')
        length = headers.get('content-length')
        if length is not None and (int(length) < 0 or int(length) > maximum):
            raise HttpError(413, 'request too large')
        data = bytearray()
        while True:
            event = await asyncio.wait_for(receive(), timeout=10)
            if event['type'] == 'http.disconnect':
                raise HttpError(400, 'client disconnected')
            data.extend(event.get('body', b''))
            if len(data) > maximum:
                raise HttpError(413, 'request too large')
            if not event.get('more_body', False):
                break
        if length is not None and len(data) != int(length):
            raise HttpError(400, 'content length mismatch')
        value = json.loads(data)
        if not isinstance(value, dict):
            raise ValueError('object required')
        return value

    def route(self, method, path):
        if method == 'GET' and path in ('/healthz', '/readyz', '/metrics'):
            return path[1:], self.health
        if method == 'POST' and path == '/v1/pairing/redeem':
            return 'pairing.redeem', self.redeem
        if path in ('/v1/credentials', '/v1/pairing/invite', '/v1/credentials/rotate', '/v1/credentials/revoke'):
            if method != ('GET' if path == '/v1/credentials' else 'POST'):
                raise HttpError(405, 'method not allowed')
            return 'credentials', self.manage_credentials
        if method == 'POST' and path == '/v1/memories/sync':
            return 'memories.sync', self.sync
        if method == 'GET' and (path == '/v1/devices' or re.fullmatch(r'/agents/[A-Za-z0-9_-]+/card', path)):
            return 'devices.discovery', self.discovery
        if method == 'POST' and path in {f'/v1/devices/{action}' for action in ('register', 'claim', 'heartbeat', 'finish', 'abandon')}:
            return 'devices.' + path.rsplit('/', 1)[1], self.worker
        if method == 'POST' and re.fullmatch(r'/agents/[A-Za-z0-9_-]+/rpc', path):
            return 'a2a.rpc', self.rpc
        raise HttpError(404, 'not found')

    async def health(self, scope, headers, receive):
        if scope['path'] == '/metrics':
            token = headers.get('authorization', '')
            if not self.metrics_token or not hmac.compare_digest(token, 'Bearer ' + self.metrics_token):
                raise HttpError(401, 'metrics authentication required')
            return {'requests': [{'route': route, 'status': status, 'count': count}
                                 for (route, status), count in sorted(self.counts.items())],
                    'duration_ms': dict(self.duration_ms)}, 200
        if scope['path'] == '/readyz':
            def check():
                for store in (self.credentials, self.tasks, self.memories):
                    with store.connect() as db:
                        db.execute('SELECT 1').fetchone()
            await asyncio.to_thread(check)
        return {'ok': True, 'protocol': 1}, 200

    async def redeem(self, scope, headers, receive):
        peer = (scope.get('client') or ('unknown',))[0]
        if not self.pairing_rates.allow(peer):
            raise HttpError(429, 'pairing rate exceeded')
        body = await self.body(headers, receive, 4096)
        return await asyncio.to_thread(self.credentials.redeem, body.get('code')), 200

    async def manage_credentials(self, scope, headers, receive):
        identity = await self.identity(headers)
        path = scope['path']
        if path == '/v1/credentials':
            return {'credentials': await asyncio.to_thread(self.credentials.list, identity['user_id'])}, 200
        body = await self.body(headers, receive, 4096)
        if path.endswith('/invite'):
            return await asyncio.to_thread(self.credentials.invite, identity, body.get('device_id')), 200
        if path.endswith('/rotate'):
            return await asyncio.to_thread(self.credentials.rotate, identity), 200
        return await asyncio.to_thread(self.credentials.revoke, identity['user_id'], body.get('id')), 200

    async def discovery(self, scope, headers, receive):
        identity = await self.identity(headers, 'tasks')
        if scope['path'] == '/v1/devices':
            return {'devices': await asyncio.to_thread(self.tasks.devices, identity['user_id'])}, 200
        try:
            return await asyncio.to_thread(self.tasks.card, identity['user_id'], scope['path'].split('/')[2], self.base_url), 200
        except RpcError:
            raise HttpError(404, 'agent not found')

    async def worker(self, scope, headers, receive):
        identity = await self.identity(headers, 'tasks')
        body = await self.body(headers, receive, 512 * 1024)
        user, device = identity['user_id'], identity['device_id']
        action = scope['path'].rsplit('/', 1)[1]
        if action == 'register':
            args = (body,)
        elif action == 'claim':
            args = ()
        elif action == 'finish':
            args = (body,)
        else:
            args = (body.get('id'), body.get('attempt'))
        return await asyncio.to_thread(getattr(self.tasks, action), user, device, *args), 200

    async def rpc(self, scope, headers, receive):
        identity = await self.identity(headers, 'tasks')
        body = await self.body(headers, receive, 512 * 1024)
        request_id = body.get('id')
        try:
            if body.get('jsonrpc') != '2.0' or request_id is None:
                raise ValueError('invalid JSON-RPC envelope')
            if headers.get('a2a-version') != '1.0':
                raise RpcError(-32009, 'unsupported A2A version')
            params = body.get('params', {})
            if not isinstance(params, dict):
                raise ValueError('invalid params')
            user, target = identity['user_id'], scope['path'].split('/')[2]
            method = body.get('method')
            if method == 'SendMessage':
                configuration = params.get('configuration', {})
                if not isinstance(configuration, dict) or type(configuration.get('returnImmediately', False)) is not bool:
                    raise ValueError('invalid send configuration')
                result = await asyncio.to_thread(self.tasks.send, user, target, params)
                if not configuration.get('returnImmediately', False):
                    while result['task']['status']['state'] not in TERMINAL | INTERRUPTED:
                        await asyncio.sleep(0.25)
                        result = {'task': await asyncio.to_thread(self.tasks.get, user, target, result['task']['id'])}
            elif method == 'companion/GetMessageReceipt':
                result = await asyncio.to_thread(self.tasks.receipt, user, target, params.get('messageId'))
            elif method in ('GetTask', 'CancelTask'):
                operation = self.tasks.get if method == 'GetTask' else self.tasks.cancel
                result = await asyncio.to_thread(operation, user, target, params.get('id'))
            else:
                raise RpcError(-32601, 'method not supported')
            return {'jsonrpc': '2.0', 'id': request_id, 'result': result}, 200
        except (ValueError, TypeError, KeyError, AttributeError) as error:
            return {'jsonrpc': '2.0', 'id': request_id, 'error': {
                'code': getattr(error, 'code', -32602), 'message': str(error)}}, 200

    async def sync(self, scope, headers, receive):
        identity = await self.identity(headers, 'memory')
        body = await self.body(headers, receive)
        if set(body) == {'version', 'records', 'cursor'} and body['version'] == 2 and body['cursor'] is not None:
            result = await asyncio.to_thread(self.memories.exchange, identity['user_id'], body['records'], body['cursor'])
        elif set(body) == {'records'}:
            result = {'records': await asyncio.to_thread(self.memories.exchange, identity['user_id'], body['records'])}
        else:
            raise ValueError('unsupported sync request')
        return result, 200


def create_app():
    os.umask(0o077)
    return CompanionService(os.environ['COMPANION_SERVICE_DATA'], os.environ['COMPANION_SERVICE_URL'],
                            os.environ.get('COMPANION_METRICS_TOKEN'))


if __name__ == '__main__':
    import uvicorn
    logging.basicConfig(level=logging.INFO, format='%(message)s')
    uvicorn.run('companion_service:create_app', factory=True, host='127.0.0.1', port=9444,
                workers=1, limit_concurrency=64, timeout_keep_alive=5, timeout_graceful_shutdown=30,
                proxy_headers=False, access_log=False)
