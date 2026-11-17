"""Loopback-only WebDAV test adapter normalizing the double-slash root."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import http.client
import os
from urllib.parse import urlsplit, urlunsplit

BACKEND_PORT = int(os.environ.get("NASMOUNT_LAB_BACKEND", "18238"))
FRONT_PORT = int(os.environ.get("NASMOUNT_LAB_FRONT", "18239"))


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def forward(self):
        target = urlsplit(self.path)
        path = urlunsplit(("", "", "/" + target.path.lstrip("/"),
                           target.query, target.fragment))
        length = int(self.headers.get("Content-Length", "0"))
        if length > 40_000_000:
            self.send_error(413)
            return
        payload = self.rfile.read(length) if length > 0 else None
        headers = {}
        for key, value in self.headers.items():
            if key.lower() not in ("host", "connection", "transfer-encoding",
                                   "content-length", "expect"):
                if key.lower() == "destination":
                    value = value.replace(f":{FRONT_PORT}", f":{BACKEND_PORT}")
                    value = value.replace(f":{BACKEND_PORT}//", f":{BACKEND_PORT}/")
                headers[key] = value
        conn = http.client.HTTPConnection("127.0.0.1", BACKEND_PORT, timeout=50)
        try:
            conn.request(self.command, path, payload, headers)
            upstream = conn.getresponse()
            result = upstream.read()
            self.send_response(upstream.status)
            for key, value in upstream.getheaders():
                if key.lower() not in ("transfer-encoding", "connection",
                                       "content-length"):
                    self.send_header(key, value)
            self.send_header("Content-Length", str(len(result)))
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(result)
        finally:
            conn.close()

    do_PROPFIND = forward
    do_GET = forward
    do_HEAD = forward
    do_PUT = forward
    do_MOVE = forward
    do_DELETE = forward
    do_MKCOL = forward
    do_COPY = forward
    do_OPTIONS = forward

    def log_message(self, fmt, *args):
        print("WEBDAV_ROOT_PROXY " + fmt % args, flush=True)


if __name__ == "__main__":
    print(f"WEBDAV_ROOT_PROXY_READY {FRONT_PORT} to {BACKEND_PORT}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", FRONT_PORT), Handler).serve_forever()
