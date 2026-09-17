import concurrent.futures
import http.client
import http.server
import struct
import threading
import unittest
from unittest.mock import patch

from doh_response_server import Handler


class InflightIsolationTest(unittest.TestCase):
    def test_readiness_counts_only_the_requested_fixture_path(self):
        started, release = threading.Event(), threading.Event()

        def delayed(_):
            started.set()
            if not release.wait(3):
                raise TimeoutError("Fixture release was not signalled")

        with http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()

            def query(path, name):
                wire = (struct.pack("!6H", 1, 0x0100, 1, 0, 0, 0) +
                        bytes([len(name)]) + name.encode() + b"\0\0\1\0\1")
                connection = http.client.HTTPConnection(*server.server_address, timeout=3)
                try:
                    connection.request("POST", path, wire)
                    response = connection.getresponse()
                    self.assertEqual(200, response.status)
                    return response.read()
                finally:
                    connection.close()

            try:
                with patch("doh_response_server.time.sleep", delayed), concurrent.futures.ThreadPoolExecutor() as workers:
                    slow = workers.submit(query, "/isolated-a", "slow")
                    try:
                        self.assertTrue(started.wait(2))
                        self.assertEqual(1, query("/isolated-a", "inflight")[-1])
                        self.assertEqual(0, query("/isolated-b", "inflight")[-1])
                    finally:
                        release.set()
                    slow.result(timeout=3)
                    self.assertEqual(0, query("/isolated-a", "inflight")[-1])
            finally:
                release.set()
                server.shutdown()
                thread.join(timeout=3)
