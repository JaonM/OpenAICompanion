import asyncio
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
import json
import importlib.util
import os
import socket
import sqlite3
import subprocess
import sys
import tempfile
import time
from pathlib import Path
import unittest
import urllib.request

from companion_service import CompanionService
from service_credentials import Credentials, digest
from service_storage import backup
from service_admin import export_user, delete_user


class ServiceTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.app = CompanionService(self.directory.name, 'https://tasks.example', 'metrics-secret')
        self.phone = self.app.credentials.issue('user', 'phone', ['tasks', 'memory'])
        self.mac = self.app.credentials.issue('user', 'mac', ['tasks'])
        self.other = self.app.credentials.issue('other', 'mac', ['tasks'])

    async def request(self, path, payload=None, token=None, method=None, headers=()):
        body = json.dumps(payload).encode() if payload is not None else b''
        emitted = []
        async def receive():
            return {'type': 'http.request', 'body': body, 'more_body': False}
        async def send(event):
            emitted.append(event)
        scope = {'type': 'http', 'method': method or ('POST' if payload is not None else 'GET'),
                 'path': path, 'client': ('127.0.0.1', 1234), 'headers': [
                     (b'content-type', b'application/json'), (b'content-length', str(len(body)).encode()),
                     *([(b'authorization', ('Bearer ' + token).encode())] if token else []), *headers]}
        await self.app(scope, receive, send)
        return emitted[0]['status'], json.loads(emitted[1]['body'])

    async def register(self, credential):
        status, _ = await self.request('/v1/devices/register', {
            'name': 'Device', 'platform': 'test', 'tools': [], 'resources': [],
            'foreground': True, 'acceptTasks': True}, credential['token'])
        self.assertEqual(status, 200)

    async def rpc(self, method, params, token=None):
        return await self.request('/agents/mac/rpc', {'jsonrpc': '2.0', 'id': 'r1', 'method': method, 'params': params},
                                  token or self.phone['token'], headers=[(b'a2a-version', b'1.0')])

    async def test_receipt_recovers_lost_ack_without_reexecuting_and_isolates_users(self):
        await self.register(self.mac)
        await self.register(self.other)
        status, sent = await self.rpc('SendMessage', {'message': {
            'messageId': 'durable-id', 'role': 'ROLE_USER', 'parts': [{'text': 'Private task'}]},
            'configuration': {'returnImmediately': True}})
        self.assertEqual(status, 200)
        task = sent['result']['task']
        _, found = await self.rpc('companion/GetMessageReceipt', {'messageId': 'durable-id'})
        self.assertEqual(found['result']['task']['id'], task['id'])
        _, absent = await self.rpc('companion/GetMessageReceipt', {'messageId': 'durable-id'}, self.other['token'])
        self.assertIsNone(absent['result']['task'])
        _, claim = await self.request('/v1/devices/claim', {}, self.mac['token'])
        job = claim['job']
        _, no_job = await self.request('/v1/devices/claim', {}, self.mac['token'])
        self.assertIsNone(no_job['job'])
        await self.request('/v1/devices/abandon', {'id': job['id'], 'attempt': job['attempt']}, self.mac['token'])
        _, unknown = await self.rpc('GetTask', {'id': job['id']})
        self.assertTrue(unknown['result']['metadata']['executionUnknown'])
        status, _ = await self.request('/v1/devices/finish', dict(job, state='TASK_STATE_COMPLETED', text='Done'), self.mac['token'])
        self.assertEqual(status, 200)
        _, resolved = await self.rpc('GetTask', {'id': job['id']})
        self.assertFalse(resolved['result']['metadata']['executionUnknown'])

    async def test_revocation_rotation_pairing_and_scope_enforcement(self):
        status, pair = await self.request('/v1/pairing/invite', {'device_id': 'tablet'}, self.phone['token'])
        self.assertEqual(status, 200)
        status, tablet = await self.request('/v1/pairing/redeem', {'code': pair['code']})
        self.assertEqual(status, 200)
        status, _ = await self.request('/v1/pairing/redeem', {'code': pair['code']})
        self.assertEqual(status, 400)
        await self.request('/v1/credentials/revoke', {'id': tablet['id']}, self.phone['token'])
        status, _ = await self.request('/v1/devices', token=tablet['token'])
        self.assertEqual(status, 401)
        status, rotated = await self.request('/v1/credentials/rotate', {}, self.phone['token'])
        self.assertEqual(status, 200)
        status, _ = await self.request('/v1/devices', token=self.phone['token'])
        self.assertEqual(status, 401)
        status, _ = await self.request('/v1/devices', token=rotated['token'])
        self.assertEqual(status, 200)
        status, _ = await self.request('/v1/memories/sync', {'records': []}, self.mac['token'])
        self.assertEqual(status, 403)
        status, _ = await self.request('/v1/credentials/revoke', {'id': self.other['id']}, rotated['token'])
        self.assertEqual(status, 400)
        self.assertIsNotNone(self.app.credentials.authenticate(self.other['token']))

    async def test_health_metrics_limits_and_privacy(self):
        self.assertEqual((await self.request('/healthz'))[0], 200)
        self.assertEqual((await self.request('/readyz'))[0], 200)
        self.assertEqual((await self.request('/metrics'))[0], 401)
        status, metrics = await self.request('/metrics', token='metrics-secret')
        self.assertEqual(status, 200)
        self.assertNotIn('token', json.dumps(metrics))
        self.assertEqual((await self.request('/v1/devices'))[0], 401)
        self.app.rates.limit = 1
        self.assertEqual((await self.request('/v1/devices', token=self.phone['token']))[0], 200)
        self.assertEqual((await self.request('/v1/devices', token=self.phone['token']))[0], 429)
        with self.assertLogs('companion.service', level='INFO') as logs:
            await self.request('/v1/pairing/redeem', {'code': 'private-code'})
        self.assertNotIn('private-code', ''.join(logs.output))

    async def test_memory_protocol_is_compatible(self):
        status, reply = await self.request('/v1/memories/sync', {'version': 2, 'records': [], 'cursor': 0}, self.phone['token'])
        self.assertEqual(status, 200)
        self.assertEqual(reply, {'version': 2, 'records': [], 'cursor': 0, 'has_more': False})

    async def test_export_and_retryable_delete_preserve_other_users(self):
        await self.register(self.mac)
        await self.register(self.other)
        await self.rpc('SendMessage', {'message': {
            'messageId': 'private-message', 'role': 'ROLE_USER', 'parts': [{'text': 'Private task'}]},
            'configuration': {'returnImmediately': True}})
        exported = export_user(self.directory.name, 'user')
        self.assertEqual(len(exported['databases']['device-tasks.sqlite']['tasks']), 1)
        self.assertNotIn('token_hash', json.dumps(exported))
        self.assertNotIn('code_hash', json.dumps(exported))
        delete_user(self.directory.name, 'user')
        delete_user(self.directory.name, 'user')
        self.assertIsNone(self.app.credentials.authenticate(self.phone['token']))
        self.assertIsNotNone(self.app.credentials.authenticate(self.other['token']))
        self.assertFalse(export_user(self.directory.name, 'user')['databases']['device-tasks.sqlite']['tasks'])
        self.assertEqual(len(self.app.tasks.devices('other')), 1)

    async def test_body_limits_disconnect_and_lifespan(self):
        events = iter([{'type': 'lifespan.startup'}, {'type': 'lifespan.shutdown'}])
        sent = []
        async def receive():
            return next(events)
        async def send(event):
            sent.append(event)
        await self.app({'type': 'lifespan'}, receive, send)
        self.assertEqual([event['type'] for event in sent],
                         ['lifespan.startup.complete', 'lifespan.shutdown.complete'])
        from companion_service import HttpError
        async def large_chunk():
            return {'type': 'http.request', 'body': b'x' * 11}
        with self.assertRaises(HttpError) as rejected:
            await self.app.body({'content-type': 'application/json'}, large_chunk, 10)
        self.assertEqual(rejected.exception.status, 413)
        async def disconnected():
            return {'type': 'http.disconnect'}
        with self.assertRaises(HttpError) as rejected:
            await self.app.body({'content-type': 'application/json'}, disconnected)
        self.assertEqual(rejected.exception.status, 400)

    async def test_full_backup_restores_credentials_tasks_and_memory(self):
        await self.register(self.mac)
        _, sent = await self.rpc('SendMessage', {'message': {
            'messageId': 'restore-message', 'role': 'ROLE_USER', 'parts': [{'text': 'Restore task'}]},
            'configuration': {'returnImmediately': True}})
        task_id = sent['result']['task']['id']
        _, claimed = await self.request('/v1/devices/claim', {}, self.mac['token'])
        self.app.memories.exchange('user', [{'id': 'a' * 32, 'author': 'b' * 32, 'revision': 1, 'memory': None}])
        self.app.credentials.revoke('other', self.other['id'])
        with tempfile.TemporaryDirectory() as restored_directory:
            for name in ('credentials.sqlite', 'device-tasks.sqlite', 'memory-sync.sqlite'):
                backup(str(Path(self.directory.name) / name), str(Path(restored_directory) / name))
            restored = CompanionService(restored_directory, 'https://tasks.example')
            self.assertIsNotNone(restored.credentials.authenticate(self.phone['token']))
            self.assertIsNone(restored.credentials.authenticate(self.other['token']))
            task = restored.tasks.get('user', 'mac', task_id)
            self.assertEqual(task['status']['state'], 'TASK_STATE_WORKING')
            self.assertIsNone(restored.tasks.claim('user', 'mac')['job'])
            receipt = restored.tasks.receipt('user', 'mac', 'restore-message')
            self.assertEqual(receipt['task']['id'], task_id)
            self.assertEqual(len(restored.memories.exchange('user', [])), 1)
            self.assertEqual(restored.memories.exchange('other', []), [])
            restored.tasks.finish('user', 'mac', dict(claimed['job'], state='TASK_STATE_COMPLETED', text='Restored'))
            self.assertEqual(restored.tasks.get('user', 'mac', task_id)['status']['state'], 'TASK_STATE_COMPLETED')


class CredentialTests(unittest.TestCase):
    def test_pairing_is_atomic_and_tokens_are_hashed(self):
        with tempfile.TemporaryDirectory() as directory:
            store = Credentials(directory + '/credentials.sqlite')
            issued = store.issue('user', 'phone', ['tasks'])
            identity = store.authenticate(issued['token'])
            code = store.invite(identity, 'mac')['code']
            def redeem():
                try:
                    return store.redeem(code)
                except ValueError:
                    return None
            with ThreadPoolExecutor(2) as executor:
                results = list(executor.map(lambda _: redeem(), range(2)))
            self.assertEqual(sum(result is not None for result in results), 1)
            with store.connect() as db:
                self.assertEqual(db.execute('SELECT token_hash FROM credentials WHERE id=?', (issued['id'],)).fetchone()[0], digest(issued['token']))
            self.assertNotIn(issued['token'].encode(), Path(store.path).read_bytes())
            copy = directory + '/backup.sqlite'
            backup(store.path, copy)
            self.assertIsNotNone(Credentials(copy).authenticate(issued['token']))
            with closing(sqlite3.connect(copy)) as db, db:
                db.execute('PRAGMA user_version=99')
            with self.assertRaises(RuntimeError):
                Credentials(copy)


@unittest.skipUnless(importlib.util.find_spec('uvicorn'), 'install requirements-service.txt for runtime smoke test')
class RuntimeTests(unittest.TestCase):
    def test_uvicorn_serves_readiness_and_shuts_down_cleanly(self):
        with tempfile.TemporaryDirectory() as directory:
            with socket.socket() as listener:
                listener.bind(('127.0.0.1', 0))
                port = listener.getsockname()[1]
            environment = dict(os.environ, COMPANION_SERVICE_DATA=directory,
                               COMPANION_SERVICE_URL=f'http://127.0.0.1:{port}')
            process = subprocess.Popen([sys.executable, '-m', 'uvicorn', 'companion_service:create_app',
                '--factory', '--host', '127.0.0.1', '--port', str(port), '--no-access-log'],
                cwd=Path(__file__).parent, env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                deadline = time.monotonic() + 10
                while True:
                    try:
                        with urllib.request.urlopen(f'http://127.0.0.1:{port}/readyz', timeout=1) as reply:
                            self.assertEqual(reply.status, 200)
                            self.assertTrue(json.load(reply)['ok'])
                        break
                    except OSError:
                        if process.poll() is not None or time.monotonic() > deadline:
                            self.fail('Uvicorn did not become ready')
                        time.sleep(0.05)
            finally:
                process.terminate()
                try:
                    _, errors = process.communicate(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.communicate()
                    self.fail('Uvicorn did not shut down')
            self.assertIn('Application shutdown complete', errors)


if __name__ == '__main__':
    unittest.main()
