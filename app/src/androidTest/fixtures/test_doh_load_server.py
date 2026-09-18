import struct
import unittest
from doh_load_server import response


def query():
    return struct.pack('!6H', 0x1234, 0x0100, 1, 0, 0, 0) + b'\x06fast-1\x04load\x04test\x00\x00\x01\x00\x01'


class LoadFixtureTest(unittest.TestCase):
    def test_answer_preserves_transaction_and_question_with_controlled_address(self):
        request = query()
        reply = response(request)
        self.assertEqual((0x1234, 0x8180, 1, 1, 0, 0), struct.unpack('!6H', reply[:12]))
        self.assertEqual(request[12:], reply[12:len(request)])
        self.assertEqual((0xC00C, 1, 1, 0, 4, 192, 0, 2, 42), struct.unpack('!HHHIH4B', reply[len(request):]))

    def test_invalid_questions_cannot_be_counted_as_success(self):
        for request in [b'bad', query()[:-1], query() + b'extra', query()[:12] + b'\xc0\x0c\x00\x01\x00\x01',
                        query()[:-4] + b'\x00\x1c\x00\x01']:
            with self.subTest(request=request), self.assertRaises((ValueError, IndexError)):
                response(request)


if __name__ == '__main__':
    unittest.main()
