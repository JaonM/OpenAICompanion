#!/usr/bin/env python3
"""Authenticated A2A 1.0 gateway and durable, single-device task queue."""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hmac
import json
import os
import re
import sqlite3
import ssl
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BODY = 512 * 1024
TERMINAL = {'TASK_STATE_COMPLETED', 'TASK_STATE_FAILED', 'TASK_STATE_CANCELED', 'TASK_STATE_REJECTED'}
INTERRUPTED = {'TASK_STATE_INPUT_REQUIRED', 'TASK_STATE_AUTH_REQUIRED'}
LEASE_SECONDS = 120


def encode(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode()


def text(value, maximum=16384):
    if not isinstance(value, str) or not value.strip() or len(value.encode()) > maximum:
        raise ValueError('invalid or oversized text')
    return value


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,100}', value):
        raise ValueError('invalid identifier')
    return value


def message(body, task_id=None, context_id=None):
    result = {'messageId': uuid.uuid4().hex, 'role': 'ROLE_AGENT', 'parts': [{'text': body}]}
    if task_id:
        result.update(taskId=task_id, contextId=context_id)
    return result


class RpcError(ValueError):
    def __init__(self, code, detail):
        self.code = code
        super().__init__(detail)


class TaskStore:
    def __init__(self, path):
        self.path = path
        with self.connect() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS devices(
                    user_id TEXT, id TEXT, descriptor TEXT NOT NULL, seen REAL NOT NULL,
                    PRIMARY KEY(user_id,id));
                CREATE TABLE IF NOT EXISTS tasks(
                    user_id TEXT, id TEXT, device_id TEXT NOT NULL, context_id TEXT NOT NULL,
                    state TEXT NOT NULL, history TEXT NOT NULL, checkpoint TEXT NOT NULL DEFAULT '[]',
                    output TEXT, attempt TEXT, lease REAL, updated REAL NOT NULL, unknown INTEGER DEFAULT 0,
                    PRIMARY KEY(user_id,id));
                CREATE TABLE IF NOT EXISTS messages(
                    user_id TEXT, device_id TEXT, message_id TEXT, payload TEXT NOT NULL, task_id TEXT NOT NULL,
                    PRIMARY KEY(user_id,device_id,message_id));
                CREATE TABLE IF NOT EXISTS completions(
                    user_id TEXT, task_id TEXT, attempt TEXT, payload TEXT NOT NULL,
                    PRIMARY KEY(user_id,task_id,attempt));
                CREATE INDEX IF NOT EXISTS task_queue ON tasks(user_id,device_id,state,updated);
            ''')

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            with db:
                db.execute('BEGIN IMMEDIATE')
                yield db
        finally:
            db.close()

    def register(self, user, device, descriptor):
        if not isinstance(descriptor, dict):
            raise ValueError('invalid descriptor')
        text(descriptor.get('name'), 200)
        text(descriptor.get('platform'), 50)
        tools = descriptor.get('tools')
        if not isinstance(tools, list) or len(tools) > 256:
            raise ValueError('invalid tools')
        for tool in tools:
            if not isinstance(tool, dict):
                raise ValueError('invalid tool')
            text(tool.get('name'), 200)
            text(tool.get('description') or tool['name'], 4096)
            if type(tool.get('requiresForeground')) is not bool:
                raise ValueError('missing execution policy')
        if type(descriptor.get('foreground')) is not bool or type(descriptor.get('acceptTasks')) is not bool:
            raise ValueError('missing device availability')
        # Resources are declared by the trusted device host, never the model.
        resources = descriptor.get('resources', [])
        if not isinstance(resources, list) or len(resources) > 256:
            raise ValueError('invalid resources')
        for resource in resources:
            text(resource, 200)
        if len(encode(descriptor)) > 65536:
            raise ValueError('descriptor too large')
        with self.connect() as db:
            db.execute('INSERT INTO devices VALUES(?,?,?,?) ON CONFLICT(user_id,id) DO UPDATE SET descriptor=excluded.descriptor,seen=excluded.seen',
                       (user, device, encode(descriptor).decode(), time.time()))
        return {'ok': True, 'id': device}

    def devices(self, user):
        with self.connect() as db:
            return [{'id': row['id'], **json.loads(row['descriptor']), 'online': time.time() - row['seen'] < 60}
                    for row in db.execute('SELECT * FROM devices WHERE user_id=? ORDER BY id', (user,))]

    def card(self, user, device, base):
        item = next((d for d in self.devices(user) if d['id'] == device), None)
        if item is None:
            raise RpcError(-32001, 'agent not found')
        return {'name': item['name'], 'version': '1.0.0', 'description': 'Personal device task executor',
                'supportedInterfaces': [{'url': f'{base}/agents/{device}/rpc', 'protocolBinding': 'JSONRPC', 'protocolVersion': '1.0'}],
                'capabilities': {'streaming': False, 'pushNotifications': False},
                'defaultInputModes': ['text/plain'], 'defaultOutputModes': ['text/plain'],
                'securitySchemes': {'bearer': {'httpAuthSecurityScheme': {'scheme': 'Bearer'}}},
                'securityRequirements': [{'schemes': {'bearer': []}}],
                'skills': [{'id': t['name'], 'name': t['name'], 'description': t['description'], 'tags': [item['platform']]}
                           for t in item['tools']] + [{'id': 'model.complete', 'name': 'model.complete', 'description': 'Process explicitly supplied text', 'tags': ['text']}]}

    def _expire(self, db):
        db.execute("UPDATE tasks SET unknown=1,output='执行结果未知：设备失联；不会自动重派或重试',updated=? WHERE state='TASK_STATE_WORKING' AND unknown=0 AND lease<?",
                   (time.time(), time.time()))

    def _task(self, db, user, device, task_id):
        row = db.execute('SELECT * FROM tasks WHERE user_id=? AND device_id=? AND id=?', (user, device, task_id)).fetchone()
        if row is None:
            raise RpcError(-32001, 'task not found')
        return row

    def view(self, row):
        status = {'state': row['state'], 'timestamp': datetime.fromtimestamp(row['updated'], timezone.utc).isoformat()}
        if row['output']:
            status['message'] = message(row['output'], row['id'], row['context_id'])
        result = {'id': row['id'], 'contextId': row['context_id'], 'status': status,
                  'metadata': {'deviceId': row['device_id'], 'executionUnknown': bool(row['unknown'])}}
        if row['state'] == 'TASK_STATE_COMPLETED':
            result['artifacts'] = [{'artifactId': row['id'] + '-result', 'parts': [{'text': row['output'] or ''}]}]
        return result

    def send(self, user, device, params):
        msg = params.get('message')
        if not isinstance(msg, dict) or msg.get('role') != 'ROLE_USER':
            raise ValueError('expected user message')
        mid = text(msg.get('messageId'), 200)
        parts = msg.get('parts')
        if not isinstance(parts, list) or not parts or any(not isinstance(p, dict) or set(p) != {'text'} for p in parts):
            raise ValueError('only text parts supported')
        body = text('\n'.join(text(p['text']) for p in parts))
        payload = encode(msg).decode()
        with self.connect() as db:
            if not db.execute('SELECT 1 FROM devices WHERE user_id=? AND id=?', (user, device)).fetchone():
                raise RpcError(-32001, 'agent not found')
            prior = db.execute('SELECT * FROM messages WHERE user_id=? AND device_id=? AND message_id=?', (user, device, mid)).fetchone()
            if prior:
                if prior['payload'] != payload:
                    raise ValueError('message id reused with different payload')
                return {'task': self.view(self._task(db, user, device, prior['task_id']))}
            tid = msg.get('taskId')
            if tid:
                row = self._task(db, user, device, tid)
                if row['state'] not in INTERRUPTED:
                    raise ValueError('task is not waiting for input')
                if msg.get('contextId', row['context_id']) != row['context_id']:
                    raise ValueError('context mismatch')
                history = json.loads(row['history']) + [msg]
                if len(history) > 32 or len(encode(history)) > 65536:
                    raise ValueError('interaction limit reached')
                db.execute("UPDATE tasks SET history=?,state='TASK_STATE_SUBMITTED',output=NULL,attempt=NULL,lease=NULL,updated=? WHERE user_id=? AND id=?",
                           (encode(history).decode(), time.time(), user, tid))
            else:
                tid = uuid.uuid4().hex
                context = msg.get('contextId') or uuid.uuid4().hex
                text(context, 200)
                db.execute('INSERT INTO tasks(user_id,id,device_id,context_id,state,history,updated) VALUES(?,?,?,?,?,?,?)',
                           (user, tid, device, context, 'TASK_STATE_SUBMITTED', encode([msg]).decode(), time.time()))
            db.execute('INSERT INTO messages VALUES(?,?,?,?,?)', (user, device, mid, payload, tid))
            return {'task': self.view(self._task(db, user, device, tid))}

    def get(self, user, device, task_id):
        with self.connect() as db:
            self._expire(db)
            return self.view(self._task(db, user, device, task_id))

    def cancel(self, user, device, task_id):
        with self.connect() as db:
            self._expire(db)
            row = self._task(db, user, device, task_id)
            if row['state'] == 'TASK_STATE_CANCELED':
                return self.view(row)
            if row['state'] == 'TASK_STATE_WORKING' or row['state'] in TERMINAL:
                raise RpcError(-32002, 'task cannot be canceled while an operation is running or after termination')
            db.execute("UPDATE tasks SET state='TASK_STATE_CANCELED',updated=? WHERE user_id=? AND id=?", (time.time(), user, task_id))
            return self.view(self._task(db, user, device, task_id))

    def claim(self, user, device):
        with self.connect() as db:
            self._expire(db)
            d = db.execute('SELECT * FROM devices WHERE user_id=? AND id=?', (user, device)).fetchone()
            if d is None or time.time() - d['seen'] >= 60:
                return {'job': None}
            desc = json.loads(d['descriptor'])
            if not desc['acceptTasks'] or not desc['foreground']:
                return {'job': None}
            if db.execute("SELECT 1 FROM tasks WHERE user_id=? AND device_id=? AND state='TASK_STATE_WORKING' AND unknown=0", (user, device)).fetchone():
                return {'job': None}
            row = db.execute("SELECT * FROM tasks WHERE user_id=? AND device_id=? AND state='TASK_STATE_SUBMITTED' ORDER BY updated LIMIT 1", (user, device)).fetchone()
            if row is None:
                return {'job': None}
            attempt = uuid.uuid4().hex
            db.execute("UPDATE tasks SET state='TASK_STATE_WORKING',attempt=?,lease=?,updated=? WHERE user_id=? AND id=?",
                       (attempt, time.time() + LEASE_SECONDS, time.time(), user, row['id']))
            return {'job': {'id': row['id'], 'attempt': attempt, 'contextId': row['context_id'],
                            'input': '\n'.join(p['text'] for p in json.loads(row['history'])[-1]['parts']),
                            'checkpoint': json.loads(row['checkpoint'])}}

    def heartbeat(self, user, device, task_id, attempt):
        with self.connect() as db:
            row = self._task(db, user, device, task_id)
            if row['attempt'] != attempt or row['state'] != 'TASK_STATE_WORKING' or row['unknown']:
                raise ValueError('execution no longer active')
            db.execute('UPDATE tasks SET lease=? WHERE user_id=? AND id=?', (time.time() + LEASE_SECONDS, user, task_id))
            db.execute('UPDATE devices SET seen=? WHERE user_id=? AND id=?', (time.time(), user, device))
            return {'ok': True}

    def finish(self, user, device, body):
        state = body.get('state')
        if state not in {'TASK_STATE_COMPLETED', 'TASK_STATE_FAILED', 'TASK_STATE_INPUT_REQUIRED'}:
            raise ValueError('invalid result state')
        output = text(body.get('text'), 65536)
        checkpoint = body.get('checkpoint', [])
        if not isinstance(checkpoint, list) or len(encode(checkpoint)) > 256 * 1024:
            raise ValueError('checkpoint too large')
        with self.connect() as db:
            row = self._task(db, user, device, body.get('id'))
            receipt_payload = {'state': state, 'text': output, 'checkpoint': checkpoint}
            receipt = db.execute('SELECT payload FROM completions WHERE user_id=? AND task_id=? AND attempt=?',
                                 (user, row['id'], body.get('attempt'))).fetchone()
            if receipt:
                if json.loads(receipt['payload']) != receipt_payload:
                    raise ValueError('conflicting completion')
                return {'ok': True}
            if row['attempt'] != body.get('attempt'):
                raise ValueError('attempt mismatch')
            if row['state'] != 'TASK_STATE_WORKING' and not row['unknown']:
                if row['state'] == state and row['output'] == output and json.loads(row['checkpoint']) == checkpoint:
                    return {'ok': True}
                raise ValueError('conflicting completion')
            db.execute('INSERT INTO completions VALUES(?,?,?,?)',
                       (user, row['id'], body['attempt'], encode(receipt_payload).decode()))
            history = json.loads(row['history']) + [message(output, row['id'], row['context_id'])]
            db.execute('UPDATE tasks SET state=?,output=?,checkpoint=?,history=?,unknown=0,updated=? WHERE user_id=? AND id=?',
                       (state, output, encode(checkpoint).decode(), encode(history).decode(), time.time(), user, row['id']))
            return {'ok': True}


def handler_for(store, credentials, base):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def reply(self, status, payload):
            raw = encode(payload)
            self.send_response(status)
            if status == 401:
                self.send_header('WWW-Authenticate', 'Bearer')
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def identity(self):
            bearer = self.headers.get('Authorization', '')
            token = bearer[7:] if bearer.startswith('Bearer ') else ''
            return next((v for k, v in credentials.items() if hmac.compare_digest(k, token)), None)

        def do_GET(self):
            identity = self.identity()
            if identity is None:
                return self.reply(401, {'error': 'authentication required'})
            try:
                if self.path == '/v1/devices':
                    return self.reply(200, {'devices': store.devices(identity['user_id'])})
                match = re.fullmatch(r'/agents/([A-Za-z0-9_-]+)/card', self.path)
                if match:
                    return self.reply(200, store.card(identity['user_id'], match[1], base))
                self.reply(404, {'error': 'not found'})
            except RpcError as error:
                self.reply(404, {'error': str(error)})

        def do_POST(self):
            identity = self.identity()
            if identity is None:
                return self.reply(401, {'error': 'authentication required'})
            request_id = None
            is_rpc = False
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= MAX_BODY:
                    return self.reply(413, {'error': 'request too large'})
                body = json.loads(self.rfile.read(length))
                if not isinstance(body, dict):
                    raise ValueError('expected object')
                user, device = identity['user_id'], identity['device_id']
                if self.path == '/v1/devices/register':
                    result = store.register(user, device, body)
                elif self.path == '/v1/devices/claim':
                    result = store.claim(user, device)
                elif self.path == '/v1/devices/heartbeat':
                    result = store.heartbeat(user, device, body.get('id'), body.get('attempt'))
                elif self.path == '/v1/devices/finish':
                    result = store.finish(user, device, body)
                else:
                    match = re.fullmatch(r'/agents/([A-Za-z0-9_-]+)/rpc', self.path)
                    if not match:
                        return self.reply(404, {'error': 'not found'})
                    is_rpc = True
                    request_id = body.get('id')
                    if body.get('jsonrpc') != '2.0' or request_id is None:
                        raise ValueError('invalid JSON-RPC envelope')
                    if self.headers.get('A2A-Version') != '1.0':
                        raise RpcError(-32009, 'unsupported A2A version')
                    params = body.get('params', {})
                    if not isinstance(params, dict):
                        raise ValueError('invalid params')
                    method, target = body.get('method'), match[1]
                    if method == 'SendMessage':
                        configuration = params.get('configuration', {})
                        if not isinstance(configuration, dict) or type(configuration.get('returnImmediately', False)) is not bool:
                            raise ValueError('invalid send configuration')
                        result = store.send(user, target, params)
                        if not configuration.get('returnImmediately', False):
                            while result['task']['status']['state'] not in TERMINAL | INTERRUPTED:
                                time.sleep(0.25)
                                result = {'task': store.get(user, target, result['task']['id'])}
                    elif method == 'GetTask':
                        result = store.get(user, target, params.get('id'))
                    elif method == 'CancelTask':
                        result = store.cancel(user, target, params.get('id'))
                    else:
                        raise RpcError(-32601, 'method not supported')
                    result = {'jsonrpc': '2.0', 'id': request_id, 'result': result}
                self.reply(200, result)
            except (ValueError, TypeError, KeyError, AttributeError) as error:
                if is_rpc:
                    self.reply(200, {'jsonrpc': '2.0', 'id': request_id, 'error': {'code': getattr(error, 'code', -32602), 'message': str(error)}})
                else:
                    self.reply(400, {'error': str(error)})
            except sqlite3.Error:
                self.reply(503, {'error': 'store unavailable'})
    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--db', required=True)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--cert')
    parser.add_argument('--key')
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=9444)
    args = parser.parse_args()
    credentials = json.loads(os.environ['DEVICE_TASK_TOKENS'])
    if not isinstance(credentials, dict) or not credentials:
        parser.error('DEVICE_TASK_TOKENS must map tokens to {user_id,device_id}')
    for token, identity in credentials.items():
        text(token, 16000)
        text(identity['user_id'], 200)
        identifier(identity['device_id'])
    if not args.cert and (args.host != '127.0.0.1' or not args.base_url.startswith('http://127.0.0.1:')):
        parser.error('TLS required except loopback development')
    server = ThreadingHTTPServer((args.host, args.port), handler_for(TaskStore(args.db), credentials, args.base_url.rstrip('/')))
    if args.cert:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(args.cert, args.key)
        server.socket = context.wrap_socket(server.socket, server_side=True)
    server.serve_forever()


if __name__ == '__main__':
    main()
