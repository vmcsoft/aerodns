"""Loopback HTTPS DNS workload: fast=20 ms, slow/burst=2 s; no external forwarding."""
import argparse
import http.server
import json
import ssl
import struct
import subprocess
import tempfile
import threading
import time
from pathlib import Path


def question_end(query):
    if len(query) < 17 or struct.unpack('!H', query[4:6])[0] != 1:
        raise ValueError('Expected one DNS question')
    pos = 12
    while True:
        length = query[pos]
        pos += 1
        if length == 0:
            break
        if length > 63 or pos + length >= len(query):
            raise ValueError('Invalid uncompressed QNAME')
        pos += length
    if pos + 4 != len(query) or query[pos:pos + 4] != b'\x00\x01\x00\x01':
        raise ValueError('Only a plain IN A question is supported')
    return pos + 4


def response(query):
    end = question_end(query)
    return (query[:2] + struct.pack('!5H', 0x8180, 1, 1, 0, 0) + query[12:end] +
            struct.pack('!HHHIH4B', 0xC00C, 1, 1, 0, 4, 192, 0, 2, 42))


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    lock = threading.Lock()
    measurements = {}

    def do_POST(self):
        size = int(self.headers.get('Content-Length', '0'))
        if not 17 <= size <= 65535:
            self.send_error(400)
            return
        query = self.rfile.read(size)
        try:
            body = response(query)
        except (ValueError, IndexError):
            self.send_error(400)
            return
        label = query[13:13 + query[12]].decode('ascii')
        category = label.split('-', 1)[0]
        delay = 2.0 if category in ('slow', 'burst') else 0.02 if category == 'fast' else 0
        with self.lock:
            stats = self.measurements.setdefault(self.path, {'requests': {}, 'active': 0,
                'peak_active': 0, 'responses_written': 0, 'disconnected': 0, 'ports': set()})
            stats['requests'][category] = stats['requests'].get(category, 0) + 1
            stats['active'] += 1
            stats['peak_active'] = max(stats['peak_active'], stats['active'])
            stats['ports'].add(self.client_address[1])
        try:
            time.sleep(delay)
            self.send_response(200)
            self.send_header('Content-Type', 'application/dns-message')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            self.wfile.flush()
            with self.lock:
                stats['responses_written'] += 1
        except (BrokenPipeError, ConnectionResetError, ssl.SSLError):
            with self.lock:
                stats['disconnected'] += 1
            self.close_connection = True
        finally:
            with self.lock:
                stats['active'] -= 1

    def do_GET(self):
        if self.path != '/stats':
            self.send_error(404)
            return
        with self.lock:
            snapshot = {path: {**{k: v for k, v in stats.items() if k != 'ports'},
                               'tcp_connections': len(stats['ports'])}
                        for path, stats in self.measurements.items()}
            body = json.dumps(snapshot).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=18446)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='aerodns-load-fixture-') as directory:
        cert, key = Path(directory) / 'cert.pem', Path(directory) / 'key.pem'
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                        '-subj', '/CN=localhost', '-keyout', str(key), '-out', str(cert)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        server = http.server.ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
        server.daemon_threads = True
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cert, key)
        server.socket = context.wrap_socket(server.socket, server_side=True)
        print(f'Load fixture ready on loopback port {args.port}', flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            server.server_close()


if __name__ == '__main__':
    main()
