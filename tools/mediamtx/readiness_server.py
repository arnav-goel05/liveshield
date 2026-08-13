#!/usr/bin/env python3
"""Payload-free test handshake: 204 only after a host-owned marker exists."""
import argparse
import socketserver
from pathlib import Path

def serve(marker: Path, port: int) -> None:
    class Handler(socketserver.BaseRequestHandler):
        def handle(self):
            self.request.sendall(b"\x01" if marker.is_file() else b"\x00")
    socketserver.ThreadingTCPServer.allow_reuse_address = True
    socketserver.ThreadingTCPServer(("0.0.0.0", port), Handler).serve_forever()

if __name__ == "__main__":
    parser=argparse.ArgumentParser(); parser.add_argument("--marker",type=Path,required=True)
    parser.add_argument("--port",type=int,default=18765); args=parser.parse_args()
    serve(args.marker,args.port)
