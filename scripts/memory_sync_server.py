#!/usr/bin/env python3
"""Small HTTPS memory relay. Set MEMORY_SYNC_TOKENS to {"token":"user-id"}."""

import argparse
import hmac
import json
import os
import sqlite3
import ssl
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BODY = 4 * 1024 * 1024
MAX_RECORDS = 10_000


def valid_record(record):
    if not isinstance(record, dict) or set(record) != {"id", "revision", "author", "memory"}:
        return False
    for key in ("id", "author"):
        value = record[key]
        if not isinstance(value, str) or len(value) != 32 or any(c not in "0123456789abcdef" for c in value):
            return False
    if type(record["revision"]) is not int or not 0 < record["revision"] <= int(time.time() * 1000) + 86_400_000:
        return False
    memory = record["memory"]
    if memory is None:
        return True
    if not isinstance(memory, dict) or set(memory) != {"tier", "topic_id", "content", "source", "status", "created_at", "updated_at", "expires_at"}:
        return False
    return (memory["tier"] in ("medium", "long") and memory["status"] in ("active", "archived")
            and isinstance(memory["content"], str) and 0 < len(memory["content"]) <= 16_384
            and isinstance(memory["source"], str) and len(memory["source"]) <= 1024
            and (memory["topic_id"] is None or isinstance(memory["topic_id"], str))
            and all(type(memory[key]) is int for key in ("created_at", "updated_at"))
            and (memory["expires_at"] is None or type(memory["expires_at"]) is int))


class RelayStore:
    def __init__(self, path):
        self.path = path
        with self.connect() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS records (
                user_id TEXT NOT NULL, id TEXT NOT NULL, revision INTEGER NOT NULL,
                author TEXT NOT NULL, memory_json TEXT, PRIMARY KEY(user_id,id))""")

    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.execute("PRAGMA busy_timeout=10000")
        return db

    def exchange(self, user_id, incoming):
        if not isinstance(incoming, list) or len(incoming) > MAX_RECORDS or not all(map(valid_record, incoming)):
            raise ValueError("invalid memory sync records")
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            for record in incoming:
                current = db.execute("SELECT revision,author,memory_json FROM records WHERE user_id=? AND id=?",
                                     (user_id, record["id"])).fetchone()
                if current and current[2] is None and record["memory"] is not None:
                    continue
                if current and current[:2] >= (record["revision"], record["author"]) and not (record["memory"] is None and current[2] is not None):
                    continue
                db.execute("""INSERT INTO records(user_id,id,revision,author,memory_json)
                    VALUES (?,?,?,?,?) ON CONFLICT(user_id,id) DO UPDATE SET
                    revision=excluded.revision,author=excluded.author,memory_json=excluded.memory_json""",
                    (user_id, record["id"], record["revision"], record["author"],
                     json.dumps(record["memory"], ensure_ascii=False, separators=(",", ":"))
                     if record["memory"] is not None else None))
            rows = db.execute("SELECT id,revision,author,memory_json FROM records WHERE user_id=? ORDER BY id",
                              (user_id,)).fetchall()
        return [{"id": row[0], "revision": row[1], "author": row[2],
                 "memory": json.loads(row[3]) if row[3] is not None else None} for row in rows]


def handler_for(store, tokens):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            # Never log bearer tokens or memory payloads.
            pass

        def do_POST(self):
            if self.path != "/v1/memories/sync":
                return self.send_error(404)
            authorization = self.headers.get("Authorization", "")
            token = authorization[7:] if authorization.startswith("Bearer ") else ""
            user_id = next((user for secret, user in tokens.items()
                            if hmac.compare_digest(token, secret)), None)
            if not user_id:
                return self.send_error(401)
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= MAX_BODY:
                    return self.send_error(413)
                payload = json.loads(self.rfile.read(length))
                if not isinstance(payload, dict) or set(payload) != {"records"}:
                    raise ValueError("invalid request")
                records = store.exchange(user_id, payload["records"])
                encoded = json.dumps({"records": records}, ensure_ascii=False,
                                     separators=(",", ":")).encode()
                if len(encoded) > MAX_BODY:
                    return self.send_error(413)
            except (ValueError, json.JSONDecodeError):
                return self.send_error(400)
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)
    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", required=True)
    parser.add_argument("--cert", required=True)
    parser.add_argument("--key", required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9443)
    args = parser.parse_args()
    tokens = json.loads(os.environ["MEMORY_SYNC_TOKENS"])
    if not isinstance(tokens, dict) or not tokens or any(not token or not user for token, user in tokens.items()):
        parser.error("MEMORY_SYNC_TOKENS must map nonempty bearer tokens to user ids")
    server = ThreadingHTTPServer((args.host, args.port), handler_for(RelayStore(args.db), tokens))
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(args.cert, args.key)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
