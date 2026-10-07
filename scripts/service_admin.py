#!/usr/bin/env python3
"""Offline backup/export/deletion tools. Stop the service before export or deletion."""
import argparse
from contextlib import closing
import json
import os
from pathlib import Path
import sqlite3

from service_storage import backup

TABLES = {
    'credentials.sqlite': ('credentials', 'invitations'),
    'device-tasks.sqlite': ('devices', 'tasks', 'messages', 'completions'),
    'memory-sync.sqlite': ('records', 'changes'),
}


def export_user(directory, user):
    result = {'version': 1, 'user_id': user, 'databases': {}}
    for name, tables in TABLES.items():
        path = Path(directory) / name
        if not path.is_file():
            continue
        with closing(sqlite3.connect(f'file:{path}?mode=ro', uri=True)) as db:
            db.row_factory = sqlite3.Row
            result['databases'][name] = {table: [
                {key: row[key] for key in row.keys() if key not in ('token_hash', 'code_hash')}
                for row in db.execute(f'SELECT * FROM {table} WHERE user_id=?', (user,))] for table in tables}
    return result


def delete_user(directory, user):
    # Revoke first. If deletion is interrupted, access stays revoked and this command is retryable.
    path = Path(directory) / 'credentials.sqlite'
    if path.is_file():
        with closing(sqlite3.connect(path)) as db, db:
            db.execute('UPDATE credentials SET revoked=COALESCE(revoked,unixepoch()) WHERE user_id=?', (user,))
            db.execute('DELETE FROM invitations WHERE user_id=?', (user,))
    for name, tables in TABLES.items():
        if name == 'credentials.sqlite':
            continue  # Retain revoked hashes to prevent identity/pairing reuse by stale clients.
        path = Path(directory) / name
        if not path.is_file():
            continue
        with closing(sqlite3.connect(path)) as db, db:
            db.execute('PRAGMA secure_delete=ON')
            for table in reversed(tables):
                db.execute(f'DELETE FROM {table} WHERE user_id=?', (user,))
            db.commit()
            db.execute('PRAGMA wal_checkpoint(TRUNCATE)')
            db.execute('VACUUM')


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', required=True)
    commands = parser.add_subparsers(dest='command', required=True)
    snapshots = commands.add_parser('backup')
    snapshots.add_argument('--output', required=True)
    export = commands.add_parser('export')
    export.add_argument('--user', required=True)
    export.add_argument('--output', required=True)
    export.add_argument('--service-stopped', action='store_true', required=True)
    delete = commands.add_parser('delete-user')
    delete.add_argument('--user', required=True)
    delete.add_argument('--confirm-user', required=True)
    delete.add_argument('--service-stopped', action='store_true', required=True)
    args = parser.parse_args()
    if args.command == 'backup':
        target = Path(args.output)
        target.mkdir(mode=0o700, parents=True, exist_ok=False)
        for name in TABLES:
            source = Path(args.data) / name
            if source.is_file():
                backup(str(source), str(target / name))
    elif args.command == 'export':
        with open(args.output, 'x', encoding='utf-8') as output:
            json.dump(export_user(args.data, args.user), output, ensure_ascii=False)
    else:
        if args.confirm_user != args.user:
            parser.error('confirmation must match user id')
        delete_user(args.data, args.user)


if __name__ == '__main__':
    main()
