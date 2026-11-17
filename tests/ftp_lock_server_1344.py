"""Disposable local FTP server, only for the NAS Mount exclusive MKD test."""
import os
import pathlib
import sys

if len(sys.argv) != 3:
    raise SystemExit("Usage: server.py <isolated-pydeps> <QA-root>")

sys.path.insert(0, sys.argv[1])
from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import FTPHandler
from pyftpdlib.servers import FTPServer

root = pathlib.Path(sys.argv[2]).resolve()
if "QA_lock_1344" not in root.parts:
    raise SystemExit("Refusing to run outside the QA_lock_1344 directory")
root.mkdir(parents=True, exist_ok=True)
authorizer = DummyAuthorizer()
authorizer.add_user("naslab", "naslab-pass", str(root), perm="elradfmwMT")
handler = FTPHandler
handler.authorizer = authorizer
handler.passive_ports = range(31000, 31020)
ftp = FTPServer(("127.0.0.1", 21944), handler)
print("LOCAL_EXCLUSIVE_MKD_FTP_READY", flush=True)
ftp.serve_forever(timeout=0.5, blocking=True, handle_exit=True)
