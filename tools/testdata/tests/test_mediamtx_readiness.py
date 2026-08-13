import socket, subprocess, tempfile, time, unittest
from pathlib import Path

class ReadinessServerTest(unittest.TestCase):
    @staticmethod
    def connect_when_listening(port):
        deadline = time.monotonic() + 2
        while True:
            try:
                return socket.create_connection(("127.0.0.1", port), timeout=.2)
            except ConnectionRefusedError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(.02)

    def test_unavailable_then_ready_and_early_exit(self):
        with tempfile.TemporaryDirectory() as directory:
            marker=Path(directory)/"ready"
            process=subprocess.Popen(["python3","tools/mediamtx/readiness_server.py",
                "--marker",str(marker),"--port","18766"])
            try:
                with self.connect_when_listening(18766) as client:
                    self.assertEqual(b"\x00",client.recv(1))
                marker.touch()
                with socket.create_connection(("127.0.0.1",18766),timeout=1) as client:
                    self.assertEqual(b"\x01",client.recv(1))
            finally:
                process.terminate(); process.wait(timeout=2)
            with self.assertRaises(OSError):
                socket.create_connection(("127.0.0.1",18766),timeout=.1)
