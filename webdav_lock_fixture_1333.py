"""Loopback-only, in-memory WebDAV cooperative lease fixture.

Never touches live NAS files. Accepts only disposable .nasmount_probe_*.xlsx
lock marker paths; rejects all other names and non-loopback connections.
"""
import http.server
import json
import re
import sys
import threading
from urllib.parse import unquote, urlsplit

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 21442
PATTERN = re.compile(r"^/(\.nasmount_probe_[0-9a-f]{32}\.xlsx\.nasmount\.lock)(/owner\.json)?$")
GATE = threading.Lock()
LOCKS = {}


class Handler(http.server.BaseHTTPRequestHandler):
    def keys(self):
        path = unquote(urlsplit(self.path).path)
        result = PATTERN.fullmatch(path)
        if result is None:
            raise ValueError("Not a disposable WebDAV lock probe path")
        return result.group(1), bool(result.group(2))

    def reply(self, code, data=b""):
        self.send_response(code)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if data:
            self.wfile.write(data)

    def do_MKCOL(self):
        try:
            key, is_owner = self.keys()
            if is_owner:
                return self.reply(400)
            with GATE:
                if key in LOCKS:
                    return self.reply(405)
                LOCKS[key] = None
            self.reply(201)
        except ValueError:
            self.reply(400)

    def do_PUT(self):
        try:
            key, is_owner = self.keys()
            if not is_owner:
                return self.reply(400)
            length = int(self.headers.get("Content-Length", "-1"))
            if length < 1 or length > 8192:
                return self.reply(400)
            data = self.rfile.read(length)
            parsed = json.loads(data)
            if not parsed.get("Id"):
                return self.reply(400)
            with GATE:
                if key not in LOCKS:
                    return self.reply(409)
                LOCKS[key] = data
            self.reply(201)
        except (ValueError, json.JSONDecodeError):
            self.reply(400)

    def do_HEAD(self):
        # The disposable document itself is deliberately NOT created.
        self.reply(404)

    def do_GET(self):
        try:
            key, is_owner = self.keys()
            if not is_owner:
                return self.reply(404)
            with GATE:
                data = LOCKS.get(key)
            if data is None:
                return self.reply(404)
            self.reply(200, data)
        except ValueError:
            self.reply(400)

    def do_DELETE(self):
        try:
            key, is_owner = self.keys()
            if is_owner:
                return self.reply(400)
            with GATE:
                if key not in LOCKS:
                    return self.reply(404)
                del LOCKS[key]
            self.reply(204)
        except ValueError:
            self.reply(400)

    def log_message(self, fmt, *args):
        print("WEBDAV_LOCK_FIXTURE " + fmt % args, flush=True)


server = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
print(f"WEBDAV_LOCK_FIXTURE_READY 127.0.0.1:{PORT}", flush=True)
try:
    server.serve_forever()
finally:
    server.server_close()
