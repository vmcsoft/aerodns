import socket
import struct
import unittest

from resolver_identity_server import answer, question


def query(kind=1):
    return struct.pack('!6H', 123, 256, 1, 0, 0, 0) + b'\x05probe\x07aerodns\x04test\0' + struct.pack('!HH', kind, 1)


class IdentityResponseTest(unittest.TestCase):
    def test_address_and_transaction_identify_the_selected_fixture(self):
        for ip in ['192.0.2.42', '192.0.2.43']:
            response = answer(query(), ip)
            self.assertEqual(123, struct.unpack('!H', response[:2])[0])
            self.assertEqual(socket.inet_aton(ip), response[-4:])
            self.assertEqual(1, struct.unpack('!H', response[6:8])[0])

    def test_failure_and_ipv6_requests_cannot_invent_an_ipv4_answer(self):
        failed = answer(query(), '192.0.2.42', failed=True)
        self.assertEqual(2, failed[3] & 15)
        self.assertEqual(0, struct.unpack('!H', failed[6:8])[0])
        empty = answer(query(28), '192.0.2.42')
        self.assertEqual(0, struct.unpack('!H', empty[6:8])[0])

    def test_edns_is_not_copied_into_the_answer_question(self):
        original = query()
        # One additional OPT record advertising an EDNS UDP payload size.
        with_edns = original[:10] + struct.pack('!H', 1) + original[12:] + b'\0' + struct.pack('!HHIH', 41, 4096, 0, 0)
        self.assertEqual(question(original), question(with_edns))
        self.assertEqual(answer(original, '192.0.2.42'), answer(with_edns, '192.0.2.42'))
