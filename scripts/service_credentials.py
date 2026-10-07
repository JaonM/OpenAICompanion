"""Revocable device credentials and one-use pairing invitations; plaintext is returned once."""
import argparse
from contextlib import contextmanager
import hashlib
import json
import os
import secrets
import sqlite3
import time
import uuid

from service_storage import migrate


def digest(token):
    return hashlib.sha256(token.encode()).hexdigest()


class Credentials:
    def __init__(self, path):
        self.path = path
        with self.connect() as db:
            migrate(db, '''
                CREATE TABLE IF NOT EXISTS credentials(
                    id TEXT PRIMARY KEY, token_hash TEXT UNIQUE NOT NULL,
                    user_id TEXT NOT NULL, device_id TEXT NOT NULL,
                    scopes TEXT NOT NULL, created REAL NOT NULL, revoked REAL);
                CREATE TABLE IF NOT EXISTS invitations(
                    code_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL, device_id TEXT NOT NULL,
                    scopes TEXT NOT NULL, expires REAL NOT NULL, consumed REAL);
            ''')

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    @staticmethod
    def validate(user, device, scopes):
        if not isinstance(user, str) or not user.strip() or len(user) > 200:
            raise ValueError('invalid user')
        if not isinstance(device, str) or not device or len(device) > 100 or any(
                c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-' for c in device):
            raise ValueError('invalid device')
        if not isinstance(scopes, list) or not scopes or not set(scopes) <= {'tasks', 'memory'}:
            raise ValueError('invalid scopes')

    def _issue(self, db, user, device, scopes):
        token, key = secrets.token_urlsafe(32), uuid.uuid4().hex
        db.execute('INSERT INTO credentials VALUES(?,?,?,?,?,?,NULL)',
                   (key, digest(token), user, device, json.dumps(scopes), time.time()))
        return {'id': key, 'token': token, 'device_id': device, 'scopes': scopes}

    def issue(self, user, device, scopes):
        self.validate(user, device, scopes)
        with self.connect() as db:
            return self._issue(db, user, device, scopes)

    def authenticate(self, token):
        if not isinstance(token, str) or not 1 <= len(token) <= 16000:
            return None
        with self.connect() as db:
            row = db.execute('SELECT * FROM credentials WHERE token_hash=? AND revoked IS NULL', (digest(token),)).fetchone()
            if row is None:
                return None
            return {'id': row['id'], 'user_id': row['user_id'], 'device_id': row['device_id'],
                    'scopes': json.loads(row['scopes'])}

    def list(self, user):
        with self.connect() as db:
            return [dict(row) | {'scopes': json.loads(row['scopes'])} for row in db.execute(
                'SELECT id,device_id,scopes,created,revoked FROM credentials WHERE user_id=? ORDER BY created', (user,))]

    def revoke(self, user, key):
        with self.connect() as db:
            if not db.execute('UPDATE credentials SET revoked=COALESCE(revoked,?) WHERE user_id=? AND id=?',
                              (time.time(), user, key)).rowcount:
                raise ValueError('credential not found')
            return {'ok': True}

    def rotate(self, identity):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            if not db.execute('UPDATE credentials SET revoked=? WHERE id=? AND revoked IS NULL',
                              (time.time(), identity['id'])).rowcount:
                raise ValueError('credential already revoked')
            return self._issue(db, identity['user_id'], identity['device_id'], identity['scopes'])

    def invite(self, identity, device):
        self.validate(identity['user_id'], device, identity['scopes'])
        code, expires = secrets.token_urlsafe(32), time.time() + 600
        with self.connect() as db:
            # A replacement identity must use rotation, not an invitation that could inherit tasks.
            if db.execute('SELECT 1 FROM credentials WHERE user_id=? AND device_id=?',
                          (identity['user_id'], device)).fetchone():
                raise ValueError('device identity already exists')
            db.execute('INSERT INTO invitations VALUES(?,?,?,?,?,NULL)',
                       (digest(code), identity['user_id'], device, json.dumps(identity['scopes']), expires))
        return {'code': code, 'expires_at': expires, 'device_id': device}

    def redeem(self, code):
        if not isinstance(code, str) or len(code) > 200:
            raise ValueError('invalid pairing code')
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT * FROM invitations WHERE code_hash=? AND consumed IS NULL AND expires>?',
                             (digest(code), time.time())).fetchone()
            if row is None or db.execute('SELECT 1 FROM credentials WHERE user_id=? AND device_id=?',
                                        (row['user_id'], row['device_id'])).fetchone():
                raise ValueError('pairing code expired or consumed')
            db.execute('UPDATE invitations SET consumed=? WHERE code_hash=?', (time.time(), digest(code)))
            return self._issue(db, row['user_id'], row['device_id'], json.loads(row['scopes']))


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--db', required=True)
    parser.add_argument('--user', required=True)
    commands = parser.add_subparsers(dest='command', required=True)
    issue = commands.add_parser('issue')
    issue.add_argument('--device', required=True)
    issue.add_argument('--scopes', nargs='+', default=['tasks', 'memory'])
    revoke = commands.add_parser('revoke')
    revoke.add_argument('--id', required=True)
    commands.add_parser('list')
    args = parser.parse_args()
    credentials = Credentials(args.db)
    if args.command == 'issue':
        result = credentials.issue(args.user, args.device, args.scopes)
    elif args.command == 'revoke':
        result = credentials.revoke(args.user, args.id)
    else:
        result = credentials.list(args.user)
    print(json.dumps(result))


if __name__ == '__main__':
    main()
