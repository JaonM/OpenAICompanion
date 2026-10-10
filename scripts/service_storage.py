"""Versioned SQLite initialization shared by the production services."""
import sqlite3
from contextlib import closing

TABLES = {
    'credentials.sqlite': ('credentials', 'invitations'),
    'device-tasks.sqlite': ('devices', 'tasks', 'messages', 'completions'),
    'memory-sync.sqlite': ('records', 'changes'),
}


def migrate(db, statements, version=1):
    current = db.execute('PRAGMA user_version').fetchone()[0]
    if current > version:
        raise RuntimeError('database belongs to a newer service; refusing downgrade')
    if current < version:
        # Legacy databases have version 0. CREATE IF NOT EXISTS adopts them without data loss.
        db.executescript('BEGIN IMMEDIATE;\n' + statements +
                         f'\nPRAGMA user_version={version};\nCOMMIT;')


def backup(source, destination):
    """SQLite online backup includes committed WAL data; never copy a live database file."""
    with closing(sqlite3.connect(source)) as db, closing(sqlite3.connect(destination)) as target:
        db.backup(target)
        if target.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
            raise RuntimeError('backup integrity check failed')
