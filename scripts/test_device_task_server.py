import concurrent.futures
import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

from device_task_server import TaskStore, RpcError, handler_for


def descriptor(name='Mac', foreground=True, accepts=True):
    return {'name': name, 'platform': 'macos', 'foreground': foreground, 'acceptTasks': accepts,
            'tools': [{'name': 'device_get_context', 'description': 'Context', 'requiresForeground': False}], 'resources': []}


def send(mid='m1', task=None, context=None, body='Do work'):
    message = {'messageId': mid, 'role': 'ROLE_USER', 'parts': [{'text': body}]}
    if task:
        message['taskId'] = task
    if context:
        message['contextId'] = context
    return {'message': message, 'configuration': {'returnImmediately': True}}


class TaskStoreTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = str(Path(self.directory.name) / 'tasks.sqlite')
        self.store = TaskStore(self.path)
        self.store.register('u1', 'mac', descriptor())

    def tearDown(self):
        self.directory.cleanup()

    def test_multiturn_checkpoint_and_idempotent_completion(self):
        task = self.store.send('u1', 'mac', send())['task']
        job = self.store.claim('u1', 'mac')['job']
        checkpoint = [{'User': {'content': 'Do work'}}, {'Assistant': {'content': 'Which branch?', 'tool_calls': []}}]
        result = {**job, 'state': 'TASK_STATE_INPUT_REQUIRED', 'text': 'Which branch?', 'checkpoint': checkpoint}
        self.store.finish('u1', 'mac', result)
        self.store.finish('u1', 'mac', result)
        reply = send('m2', task['id'], task['contextId'], 'Current branch')
        updated = self.store.send('u1', 'mac', reply)['task']
        self.assertEqual(task['id'], updated['id'])
        self.assertEqual(task['contextId'], updated['contextId'])
        # A lost reply acknowledgment is safe to replay even after the task state changes.
        self.assertEqual(updated, self.store.send('u1', 'mac', reply)['task'])
        second = self.store.claim('u1', 'mac')['job']
        self.assertEqual(checkpoint, second['checkpoint'])
        self.assertEqual('Current branch', second['input'])
        self.assertNotEqual(job['attempt'], second['attempt'])
        # A reply may arrive before the previous worker sees its upload acknowledgment.
        self.assertEqual({'ok': True}, self.store.finish('u1', 'mac', result))
        with self.assertRaises(ValueError):
            self.store.finish('u1', 'mac', {**result, 'text': 'Different result'})
        self.store.finish('u1', 'mac', {**second, 'state': 'TASK_STATE_COMPLETED', 'text': 'Passed'})
        self.assertEqual('Passed', self.store.get('u1', 'mac', task['id'])['artifacts'][0]['parts'][0]['text'])
        self.assertIsNone(self.store.claim('u1', 'mac')['job'])

    def test_concurrent_claim_only_one_executor(self):
        self.store.send('u1', 'mac', send())
        with concurrent.futures.ThreadPoolExecutor(8) as pool:
            jobs = list(pool.map(lambda _: self.store.claim('u1', 'mac')['job'], range(8)))
        self.assertEqual(1, sum(job is not None for job in jobs))

    def test_reopen_and_expired_execution_never_reclaimed(self):
        task = self.store.send('u1', 'mac', send())['task']
        job = self.store.claim('u1', 'mac')['job']
        reopened = TaskStore(self.path)
        with patch('device_task_server.time.time', return_value=10**11):
            current = reopened.get('u1', 'mac', task['id'])
            self.assertTrue(current['metadata']['executionUnknown'])
            reopened.register('u1', 'mac', descriptor())
            self.assertIsNone(reopened.claim('u1', 'mac')['job'])
        # A cached result from the original attempt may resolve an uncertain execution.
        reopened.finish('u1', 'mac', {**job, 'state': 'TASK_STATE_COMPLETED', 'text': 'Done'})
        self.assertFalse(reopened.get('u1', 'mac', task['id'])['metadata']['executionUnknown'])

    def test_identity_isolation_message_conflict_and_cancel(self):
        task = self.store.send('u1', 'mac', send())['task']
        self.assertEqual(task, self.store.send('u1', 'mac', send())['task'])
        with self.assertRaises(ValueError):
            self.store.send('u1', 'mac', send(body='Different input'))
        with self.assertRaises(RpcError):
            self.store.get('u2', 'mac', task['id'])
        with self.assertRaises(RpcError):
            self.store.get('u1', 'phone', task['id'])
        canceled = self.store.cancel('u1', 'mac', task['id'])
        self.assertEqual('TASK_STATE_CANCELED', canceled['status']['state'])
        self.assertIsNone(self.store.claim('u1', 'mac')['job'])
        with self.assertRaises(ValueError):
            self.store.send('u1', 'mac', send('m2', task['id']))

    def test_foreground_and_opt_in_required(self):
        self.store.send('u1', 'mac', send())
        for desc in (descriptor(foreground=False), descriptor(accepts=False)):
            self.store.register('u1', 'mac', desc)
            self.assertIsNone(self.store.claim('u1', 'mac')['job'])
        self.store.register('u1', 'mac', descriptor())
        job = self.store.claim('u1', 'mac')['job']
        with self.assertRaises(RpcError):
            self.store.cancel('u1', 'mac', job['id'])
        with self.assertRaises(ValueError):
            self.store.finish('u1', 'mac', {**job, 'attempt': 'forged', 'state': 'TASK_STATE_COMPLETED', 'text': 'Forged'})


class GatewayHttpTest(unittest.TestCase):
    def test_two_devices_delegate_question_reply_and_result_over_http(self):
        with tempfile.TemporaryDirectory() as directory:
            store = TaskStore(str(Path(directory) / 'tasks.sqlite'))
            credentials = {'phone-secret': {'user_id': 'u1', 'device_id': 'phone'},
                           'mac-secret': {'user_id': 'u1', 'device_id': 'mac'},
                           'other-secret': {'user_id': 'u2', 'device_id': 'other'}}
            server = ThreadingHTTPServer(('127.0.0.1', 0), handler_for(store, credentials, 'https://gateway.test'))
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            def request(path, body=None, token='phone-secret'):
                req = urllib.request.Request(f'http://127.0.0.1:{server.server_port}' + path,
                    data=None if body is None else json.dumps(body).encode(),
                    headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json', 'A2A-Version': '1.0'})
                with urllib.request.urlopen(req) as response:
                    return json.load(response)
            def rpc(method, params, token='phone-secret'):
                return request('/agents/mac/rpc', {'jsonrpc': '2.0', 'id': 'r1', 'method': method, 'params': params}, token)
            try:
                request('/v1/devices/register', descriptor('Phone', accepts=False))
                registered = request('/v1/devices/register', descriptor(), 'mac-secret')
                self.assertEqual('mac', registered['id'])
                self.assertEqual(2, len(request('/v1/devices')['devices']))
                card = request('/agents/mac/card')
                self.assertEqual('JSONRPC', card['supportedInterfaces'][0]['protocolBinding'])
                task = rpc('SendMessage', send())['result']['task']
                job = request('/v1/devices/claim', {}, 'mac-secret')['job']
                request('/v1/devices/finish', {**job, 'state': 'TASK_STATE_INPUT_REQUIRED', 'text': 'Which branch?'}, 'mac-secret')
                self.assertEqual('TASK_STATE_INPUT_REQUIRED', rpc('GetTask', {'id': task['id']})['result']['status']['state'])
                rpc('SendMessage', send('m2', task['id'], task['contextId'], 'Current'))
                second = request('/v1/devices/claim', {}, 'mac-secret')['job']
                request('/v1/devices/finish', {**second, 'state': 'TASK_STATE_COMPLETED', 'text': 'Passed'}, 'mac-secret')
                self.assertEqual('TASK_STATE_COMPLETED', rpc('GetTask', {'id': task['id']})['result']['status']['state'])
                self.assertEqual(-32001, rpc('GetTask', {'id': task['id']}, 'other-secret')['error']['code'])
                with self.assertRaises(urllib.error.HTTPError) as error:
                    request('/v1/devices', token='invalid')
                self.assertEqual(401, error.exception.code)
                error.exception.close()
            finally:
                server.shutdown(); server.server_close(); thread.join()


if __name__ == '__main__':
    unittest.main()
