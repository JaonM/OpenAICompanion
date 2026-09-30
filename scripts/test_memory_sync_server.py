import tempfile
import unittest
from pathlib import Path

from memory_sync_server import RelayStore


class RelayStoreTest(unittest.TestCase):
    def test_isolation_versions_and_tombstone(self):
        with tempfile.TemporaryDirectory() as directory:
            store = RelayStore(str(Path(directory) / "relay.sqlite"))
            record = {"id": "a" * 32, "revision": 1, "author": "b" * 32,
                      "memory": {"tier": "long", "topic_id": None, "content": "下班十八点",
                                 "source": "user", "status": "active", "created_at": 1,
                                 "updated_at": 1, "expires_at": None}}
            self.assertEqual(len(store.exchange("alice", [record])), 1)
            self.assertEqual(store.exchange("bob", []), [])
            stale = dict(record, revision=0)
            with self.assertRaises(ValueError):
                store.exchange("alice", [stale])
            deleted = dict(record, revision=2, memory=None)
            self.assertIsNone(store.exchange("alice", [deleted])[0]["memory"])
            self.assertIsNone(store.exchange("alice", [record])[0]["memory"])
            self.assertIsNone(store.exchange("alice", [dict(record, revision=3)])[0]["memory"])


if __name__ == "__main__":
    unittest.main()
