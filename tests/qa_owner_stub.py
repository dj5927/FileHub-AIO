"""Loopback-only owner marker endpoint for local Excel WinFsp QA.

No real NAS, remote files, or credential material is accessed.
"""
import http.server
import sys

port = int(sys.argv[1])


class Handler(http.server.BaseHTTPRequestHandler):
    def reply(self, code, data=b""):
        self.send_response(code)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if data:
            self.wfile.write(data)

    def do_PUT(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length < 0 or length > 8192:
            return self.reply(413)
        self.rfile.read(length)
        self.reply(201)

    def do_DELETE(self):
        self.reply(204)

    def do_HEAD(self):
        self.reply(404)

    def do_GET(self):
        self.reply(404)

    def log_message(self, fmt, *args):
        pass


server = http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler)
print("OWNER_STUB_READY", flush=True)
try:
    server.serve_forever()
finally:
    server.server_close()
