"""Loopback-only HTTPS fixture for DohResponseBoundsDeviceTest; uses an ephemeral test key."""
import argparse
import http.server
import ssl
import struct
import subprocess
import tempfile
import threading
import time
from pathlib import Path


def dns_reply(query, size):
    header = query[:2] + struct.pack("!5H", 0x8180, 1, 1, 0, 0)
    question = query[12:]
    remaining = size - len(header) - len(question) - 12
    data = bytearray()
    while remaining:
        count = min(255, remaining - 1)
        data.extend(bytes([count]) + b"x" * count)
        remaining -= count + 1
    return header + question + struct.pack("!HHHIH", 0xC00C, 16, 1, 0, len(data)) + data


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    health_counts = {}
    slow_inflight = 0
    health_lock = threading.Lock()

    def do_POST(self):
        size = int(self.headers.get("Content-Length", "0"))
        if not 12 <= size <= 65535:
            self.send_error(400)
            return
        query = self.rfile.read(size)
        # Test-generated, uncompressed QNAME; only the first label selects a fixture.
        name = query[13:13 + query[12]].decode("ascii")
        body = dns_reply(query, {"large": 65507, "overflow": 65535, "medium": 4096}.get(name, 128))
        if name == "example":
            if self.path.startswith("/health-slow"):
                with self.health_lock:
                    Handler.slow_inflight += 1
                try:
                    time.sleep(2)
                finally:
                    with self.health_lock:
                        Handler.slow_inflight -= 1
            with self.health_lock:
                count = self.health_counts.get(self.path, 0) + 1
                self.health_counts[self.path] = count
            failed = self.path.startswith("/health-fail") or (self.path.startswith("/health-cycle") and count == 2)
            header = query[:2] + struct.pack("!5H", 0x8182 if failed else 0x8180, 1, 0 if failed else 1, 0, 0)
            answer = b"" if failed else struct.pack("!HHHIH4B", 0xC00C, 1, 1, 60, 4, 93, 184, 216, 34)
            body = header + query[12:] + answer
            if self.path.startswith("/health-drop-all") or (self.path.startswith("/health-drop-first") and count == 1):
                body = b"bad"  # Rejected upstream payload: no UDP response reaches the probe.
        if name == "count":
            with self.health_lock:
                count = min(255, self.health_counts.get(self.path, 0))
            body = (query[:2] + struct.pack("!5H", 0x8180, 1, 1, 0, 0) + query[12:] +
                    struct.pack("!HHHIH4B", 0xC00C, 1, 1, 0, 4, 0, 0, 0, count))
        if name == "inflight":
            with self.health_lock:
                count = min(255, Handler.slow_inflight)
            body = (query[:2] + struct.pack("!5H", 0x8180, 1, 1, 0, 0) + query[12:] +
                    struct.pack("!HHHIH4B", 0xC00C, 1, 1, 0, 4, 0, 0, 0, count))
        if name == "short":
            body = b"bad"
        elif name == "mismatch":
            body = bytes([body[0] ^ 0xFF]) + body[1:]
        elif name == "chunked":
            body = b"x" * 1_000_000
        self.send_response(200)
        self.send_header("Content-Type", "text/html" if name == "html" else "application/dns-message")
        self.send_header("Transfer-Encoding" if name == "chunked" else "Content-Length",
                         "chunked" if name == "chunked" else str(len(body)))
        self.end_headers()
        try:
            if name == "chunked":
                for offset in range(0, len(body), 4096):
                    chunk = body[offset:offset + 4096]
                    self.wfile.write(f"{len(chunk):x}\r\n".encode() + chunk + b"\r\n")
                self.wfile.write(b"0\r\n\r\n")
            else:
                self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError, ssl.SSLError):
            self.close_connection = True  # Expected when the client rejects an oversized body.

    def log_message(self, *_):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18443)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="aerodns-response-fixture-") as directory:
        cert, key = Path(directory) / "cert.pem", Path(directory) / "key.pem"
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                        "-subj", "/CN=localhost", "-keyout", str(key), "-out", str(cert)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cert, key)
        with http.server.ThreadingHTTPServer(("127.0.0.1", args.port), Handler) as server:
            server.socket = context.wrap_socket(server.socket, server_side=True)
            print(f"Local response fixture ready on port {args.port}", flush=True)
            try:
                server.serve_forever()
            except KeyboardInterrupt:
                pass


if __name__ == "__main__":
    main()
