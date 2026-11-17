"""Isolated localhost-only FTP/CP949 server for NASMount charset QA."""
import pathlib
import sys

if len(sys.argv) != 3:
    raise SystemExit("Usage: ftp_charset_server.py <existing-pydeps> <QA-root>")
sys.path.insert(0, sys.argv[1])
from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import FTPHandler
from pyftpdlib.servers import FTPServer

root = pathlib.Path(sys.argv[2]).resolve()
if "QA_charset_1365" not in root.parts:
    raise SystemExit("Will only serve QA_charset_1365 test root")
root.mkdir(parents=True, exist_ok=True)

auth = DummyAuthorizer()
auth.add_user("naslab", "naslab-pass", str(root), perm="elradfmwMT")

class CP949Handler(FTPHandler):
    encoding = "cp949"

CP949Handler.authorizer = auth
CP949Handler.passive_ports = range(32000, 32020)
server = FTPServer(("127.0.0.1", 22944), CP949Handler)
print("LOCAL_CP949_FTP_READY", flush=True)
server.serve_forever(timeout=0.4, blocking=True, handle_exit=True)
