"""Loopback test adapter that enforces HTTP conditional writes atomically.

The stock rclone WebDAV test server returns 201 even for deliberately wrong
If-Match, so it cannot validate FileHub's cross-client overwrite safety.
This adapter is TEST-ONLY; real NAS servers must pass the same probe.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, urlunsplit, unquote
import xml.etree.ElementTree as ET
import http.client
import os
import re
import threading
import time

BACKEND = int(os.environ.get("FILEHUB_STRICT_DAV_BACKEND", "18785"))
FRONT = int(os.environ.get("FILEHUB_STRICT_DAV_FRONT", "18786"))
DENY_ROOT_WRITES = os.environ.get("FILEHUB_STRICT_DAV_DENY_ROOT_WRITES") == "1"
STAGE_BYTES_PER_SEC = int(os.environ.get(
    "FILEHUB_STRICT_DAV_STAGE_BYTES_PER_SEC", "0"))
FINALIZE_LATENCY_MS = int(os.environ.get(
    "FILEHUB_STRICT_DAV_FINALIZE_LATENCY_MS", "0"))
RESPONSE_LATENCY_MS = int(os.environ.get(
    "FILEHUB_STRICT_DAV_RESPONSE_LATENCY_MS", "0"))
EMIT_MOVE_ETAG = os.environ.get(
    "FILEHUB_STRICT_DAV_EMIT_MOVE_ETAG") == "1"
DENY_REAL_STAGE_PUT = os.environ.get(
    "FILEHUB_STRICT_DAV_DENY_REAL_STAGE_PUT") == "1"
REJECT_DELETE_IF_MATCH = os.environ.get(
    "FILEHUB_STRICT_DAV_REJECT_DELETE_IF_MATCH") == "1"
STRIP_PUT_ETAG = os.environ.get(
    "FILEHUB_STRICT_DAV_STRIP_PUT_ETAG") == "1"
STRIP_OWNER_ETAGS = os.environ.get(
    "FILEHUB_STRICT_DAV_STRIP_OWNER_ETAGS") == "1"

def is_owner(path):
    return unquote(urlsplit(path).path).rsplit("/", 1)[-1].startswith("~$")
REJECT_NAMED_NEWFILE_PUT = os.environ.get(
    "FILEHUB_STRICT_DAV_REJECT_NAMED_NEWFILE_PUT", "")
WRITE_LOCK = threading.RLock()
PER_PATH_WRITE_LOCKS = os.environ.get(
    "FILEHUB_STRICT_DAV_PER_PATH_LOCKS") == "1"
PATH_LOCKS = {}
PATH_LOCKS_GATE = threading.Lock()


def write_guard(path):
    if not PER_PATH_WRITE_LOCKS:
        return WRITE_LOCK
    with PATH_LOCKS_GATE:
        if path not in PATH_LOCKS:
            PATH_LOCKS[path] = threading.RLock()
        return PATH_LOCKS[path]


def normalized(path):
    parsed = urlsplit(path)
    clean = "/" + parsed.path.lstrip("/")
    return urlunsplit(("", "", clean, parsed.query, ""))


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def condition_ok(self, path):
        match = self.headers.get("If-Match")
        none = self.headers.get("If-None-Match")
        if not match and not none:
            return True
        conn = http.client.HTTPConnection("127.0.0.1", BACKEND, timeout=20)
        try:
            conn.request("HEAD", path)
            reply = conn.getresponse()
            present = reply.status == 200
            etag = reply.getheader("ETag")
            reply.read()
            if match and (not present or (match != "*" and etag != match)):
                return False
            if none == "*" and present:
                return False
            return True
        finally:
            conn.close()

    def directory_exists(self, path):
        conn = http.client.HTTPConnection("127.0.0.1", BACKEND, timeout=20)
        try:
            # WebDAV collections commonly redirect or reject HEAD without a
            # trailing slash. PROPFIND Depth:0 is the authoritative check.
            conn.request("PROPFIND", path, headers={"Depth": "0"})
            reply = conn.getresponse()
            exists = reply.status == 207
            reply.read()
            return exists
        finally:
            conn.close()

    def forward(self):
        request_path = normalized(self.path)
        write = self.command in ("PUT", "DELETE", "MOVE", "MKCOL", "COPY")
        guard = write_guard(request_path) if write else threading.RLock()
        with guard:
            # Reproduce Synology's refusal of valid conditional DELETE after
            # a recent upload. Explicit Explorer deletion must work without
            # an If-Match, while automatic Office replacement retains it.
            if (REJECT_DELETE_IF_MATCH and self.command == "DELETE" and
                    self.headers.get("If-Match")):
                self.send_response(412)
                self.send_header("Content-Length", "0")
                self.send_header("Connection", "close")
                self.end_headers()
                self.close_connection = True
                return
            if (DENY_REAL_STAGE_PUT and self.command == "PUT" and
                re.fullmatch(r"\.nasmount-stage-[0-9a-f]{32}",
                             urlsplit(request_path).path.rsplit("/", 1)[-1])):
                self.send_response(501)
                self.send_header("Content-Length", "0")
                self.send_header("Connection", "close")
                self.end_headers()
                self.close_connection = True
                return
            # Synology's / is a virtual list of shared folders. Some NAS
            # users can write to /Shared/* but not create files directly /.
            if DENY_ROOT_WRITES and self.command in ("PUT", "MKCOL") and \
                    len([p for p in urlsplit(request_path).path.split("/") if p]) == 1:
                self.send_response(403)
                self.send_header("Content-Length", "0")
                self.send_header("Connection", "close")
                self.end_headers()
                self.close_connection = True
                return
            if self.command == "MKCOL" and self.directory_exists(request_path):
                self.send_response(405)
                self.send_header("Content-Length", "0")
                self.send_header("Connection", "close")
                self.end_headers()
                self.close_connection = True
                return
            if write and not self.condition_ok(request_path):
                # An HTTP/1.1 persistent connection must consume (or discard
                # by closing) the rejected PUT body. Otherwise the next PUT
                # is parsed as "<old body>PUT /path" and returns HTTP 501.
                self.send_response(412)
                self.send_header("Content-Length", "0")
                self.send_header("Connection", "close")
                self.end_headers()
                self.close_connection = True
                return
            body_len = int(self.headers.get("Content-Length", "0"))
            if body_len > 100_000_000:
                self.send_error(413)
                return
            if (self.command == "PUT" and ".nasmount-stage-" in request_path
                    and STAGE_BYTES_PER_SEC > 0):
                # Lab-only slow NAS simulator: use incremental reads so
                # the Windows CopyFileEx callback must wait for network I/O
                # rather than racing to 99% via local file staging.
                received = bytearray()
                started = time.monotonic()
                while len(received) < body_len:
                    part = self.rfile.read(min(128 * 1024, body_len - len(received)))
                    if not part:
                        raise ConnectionError("progressive PUT truncated")
                    received.extend(part)
                    expected = len(received) / STAGE_BYTES_PER_SEC
                    time.sleep(max(0, started + expected - time.monotonic()))
                payload = bytes(received)
            else:
                payload = self.rfile.read(body_len) if body_len else None
            headers = {}
            for key, value in self.headers.items():
                if key.lower() in (
                    "host", "connection", "transfer-encoding", "content-length",
                    "expect", "if-match", "if-none-match"
                ):
                    continue
                if key.lower() == "destination":
                    value = value.replace(f":{FRONT}", f":{BACKEND}")
                    value = value.replace(f":{BACKEND}//", f":{BACKEND}/")
                headers[key] = value
            conn = http.client.HTTPConnection("127.0.0.1", BACKEND, timeout=35)
            try:
                conn.request(self.command, request_path, payload, headers)
                response = conn.getresponse()
                result = response.read()
                if STRIP_OWNER_ETAGS and self.command == "PROPFIND" and response.status == 207:
                    document = ET.fromstring(result)
                    for item in document.findall("{DAV:}response"):
                        if is_owner(item.findtext("{DAV:}href", "")):
                            for prop in item.findall(".//{DAV:}prop"):
                                for tag in prop.findall("{DAV:}getetag"):
                                    prop.remove(tag)
                    result = ET.tostring(document, encoding="utf-8", xml_declaration=True)
                if RESPONSE_LATENCY_MS > 0:
                    time.sleep(RESPONSE_LATENCY_MS / 1000.0)
                if FINALIZE_LATENCY_MS > 0 and (
                    (self.command == "PROPFIND" and (
                        "progressive-" in request_path or
                        ".nasmount-stage-" in request_path)) or
                    (self.command in ("MOVE", "DELETE") and
                        ".nasmount-stage-" in request_path) or
                    (self.command == "PUT" and
                        ".nasmount-stage-" in request_path and
                        "move-probe-" not in request_path)
                ):
                    time.sleep(FINALIZE_LATENCY_MS / 1000.0)
                self.send_response(response.status)
                if (EMIT_MOVE_ETAG and self.command == "MOVE" and
                        ".nasmount-stage-" in request_path and
                        "move-probe-" not in request_path and
                        response.status in (201, 204)):
                    # Optional lab-only capability: model a WebDAV NAS that
                    # includes the strong final revision with MOVE success.
                    destination = self.headers.get("Destination", "")
                    target = urlsplit(destination).path
                    verify = http.client.HTTPConnection(
                        "127.0.0.1", BACKEND, timeout=20)
                    try:
                        verify.request("HEAD", target)
                        head = verify.getresponse()
                        etag = head.getheader("ETag")
                        head.read()
                        if head.status == 200 and etag and not etag.startswith("W/"):
                            self.send_header("ETag", etag)
                    finally:
                        verify.close()
                for key, value in response.getheaders():
                    if key.lower() in ("content-length", "connection", "transfer-encoding"):
                        continue
                    if (self.command == "PUT" and STRIP_PUT_ETAG and
                            key.lower() == "etag"):
                        continue
                    if (STRIP_OWNER_ETAGS and is_owner(request_path) and
                            key.lower() == "etag"):
                        continue
                    self.send_header(key, value)
                self.send_header("Content-Length", (
                    response.getheader("Content-Length", "0")
                    if self.command == "HEAD" else str(len(result))))
                self.end_headers()
                if self.command != "HEAD":
                    self.wfile.write(result)
            finally:
                conn.close()

    do_OPTIONS = forward
    do_PROPFIND = forward
    do_HEAD = forward
    do_GET = forward
    do_PUT = forward
    do_MOVE = forward
    do_DELETE = forward
    do_MKCOL = forward
    do_COPY = forward

    def log_message(self, fmt, *args):
        print("STRICT_DAV_TEST " + fmt % args, flush=True)


if __name__ == "__main__":
    print(f"STRICT_DAV_READY front={FRONT} backend={BACKEND}", flush=True)
    print(f"STRICT_DAV_TEST_CONFIG stageBytesPerSec={STAGE_BYTES_PER_SEC} finalizeLatencyMs={FINALIZE_LATENCY_MS} emitMoveEtag={EMIT_MOVE_ETAG}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", FRONT), Handler).serve_forever()
