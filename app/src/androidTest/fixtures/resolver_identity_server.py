"""Loopback DNS/HTTPS attribution fixture. Only generated test names are logged."""
import argparse
import http.server
import json
import socket
import socketserver
import ssl
import struct
import subprocess
import tempfile
import threading
from pathlib import Path


def question(query):
    if len(query) < 12 or struct.unpack('!H', query[4:6])[0] != 1:
        raise ValueError('Expected one question')
    pos, labels = 12, []
    while True:
        size = query[pos]
        pos += 1
        if size == 0:
            break
        if size > 63 or pos + size > len(query):
            raise ValueError('Invalid label')
        labels.append(query[pos:pos + size].decode('ascii').lower())
        pos += size
    kind, cls = struct.unpack('!HH', query[pos:pos + 4])
    if cls != 1:
        raise ValueError('Expected IN question')
    return '.'.join(labels), kind, query[12:pos + 4]


def answer(query, ip, failed=False):
    _, kind, body = question(query)
    count = int(kind == 1 and not failed)
    header = query[:2] + struct.pack('!5H', 0x8182 if failed else 0x8180, 1, count, 0, 0)
    return header + body + (struct.pack('!HHHIH', 0xC00C, 1, 1, 0, 4) + socket.inet_aton(ip) if count else b'')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--listen', default='127.0.0.1')
    parser.add_argument('--dns-port', type=int, default=15353)
    parser.add_argument('--https-port', type=int, default=18444)
    parser.add_argument('--journal', type=Path, required=True)
    parser.add_argument('--cert', type=Path)
    parser.add_argument('--key', type=Path)
    args = parser.parse_args()
    lock = threading.Lock()

    def respond(query, channel, value=42, failed=False):
        name, _, _ = question(query)
        if name.endswith('.aerodns.test') or name == 'example.com':
            ip = '10.0.2.2' if name.startswith('bootstrap.') else f'192.0.2.{value}'
            if name.endswith('.aerodns.test'):
                with lock, args.journal.open('a') as out:
                    out.write(json.dumps({'name': name, 'channel': channel, 'failed': failed}) + '\n')
            return answer(query, ip, failed)
        # Optional emulator bootstrap mode needs real connectivity validation.
        # Unknown queries are forwarded but never logged or persisted.
        if channel == 'udp':
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as upstream:
                upstream.settimeout(2)
                upstream.connect(('8.8.8.8', 53))
                upstream.send(query)
                return upstream.recv(65535)
        return answer(query, '192.0.2.42', failed=True)

    class DNS(socketserver.BaseRequestHandler):
        def handle(self):
            query, sock = self.request
            try:
                sock.sendto(respond(query, 'udp'), self.client_address)
            except (ValueError, IndexError, UnicodeError, struct.error, OSError):
                pass

    class HTTPS(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            try:
                size = int(self.headers.get('Content-Length', '0'))
                if not 12 <= size <= 65535 or self.path not in ['/42', '/43', '/fail']:
                    raise ValueError('Invalid request')
                reply = respond(self.rfile.read(size), 'https' + self.path,
                                43 if self.path == '/43' else 42, self.path == '/fail')
            except (ValueError, IndexError, UnicodeError, struct.error, OSError):
                self.send_error(400)
                return
            self.send_response(200)
            self.send_header('Content-Type', 'application/dns-message')
            self.send_header('Content-Length', str(len(reply)))
            self.end_headers()
            try:
                self.wfile.write(reply)
            except OSError:
                pass

        def log_message(self, *_):
            pass

    with tempfile.TemporaryDirectory(prefix='aerodns-identity-') as directory:
        cert, key = Path(directory) / 'cert.pem', Path(directory) / 'key.pem'
        if args.cert and args.key:
            cert, key = args.cert, args.key
        else:
            subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                        '-subj', '/CN=bootstrap.aerodns.test', '-keyout', str(key), '-out', str(cert)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.load_cert_chain(cert, key)
        with socketserver.ThreadingUDPServer((args.listen, args.dns_port), DNS) as dns, \
                http.server.ThreadingHTTPServer((args.listen, args.https_port), HTTPS) as https:
            https.socket = tls.wrap_socket(https.socket, server_side=True)
            thread = threading.Thread(target=dns.serve_forever, daemon=True)
            thread.start()
            print('Resolver identity fixture ready', flush=True)
            try:
                https.serve_forever()
            finally:
                dns.shutdown()
                thread.join()


if __name__ == '__main__':
    main()
