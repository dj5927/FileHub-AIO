"""Disposable Paramiko localhost SFTP server with exclusive mkdir semantics."""
import errno
import os
import pathlib
import posixpath
import socket
import sys
import threading
import time

if len(sys.argv) != 3:
    raise SystemExit("Usage: sftp_lock_server.py <isolated-pydeps> <QA-root>")
sys.path.insert(0, sys.argv[1])
import paramiko

root = pathlib.Path(sys.argv[2]).resolve()
if "QA_lock_1344" not in root.parts:
    raise SystemExit("Refusing to serve outside QA_lock_1344")
root.mkdir(parents=True, exist_ok=True)

class Auth(paramiko.ServerInterface):
    def check_auth_password(self, username, password):
        return (paramiko.AUTH_SUCCESSFUL if username == "naslab" and
                password == "naslab-pass" else paramiko.AUTH_FAILED)
    def get_allowed_auths(self, username):
        return "password"
    def check_channel_request(self, kind, chanid):
        return (paramiko.OPEN_SUCCEEDED if kind == "session" else
                paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED)

class Files(paramiko.SFTPServerInterface):
    def real(self, path):
        normalized = posixpath.normpath("/" + path.lstrip("/"))
        resolved = (root / normalized.lstrip("/")).resolve()
        if resolved != root and root not in resolved.parents:
            raise PermissionError("Outside isolated SFTP test root")
        return resolved

    def canonicalize(self, path):
        return posixpath.normpath("/" + path.lstrip("/"))

    def _err(self, exc):
        return paramiko.SFTPServer.convert_errno(
            exc.errno if isinstance(exc, OSError) else errno.EIO)

    def stat(self, path):
        try:
            return paramiko.SFTPAttributes.from_stat(self.real(path).stat())
        except Exception as e:
            return self._err(e)

    def lstat(self, path):
        return self.stat(path)

    def list_folder(self, path):
        try:
            result = []
            for item in self.real(path).iterdir():
                attr = paramiko.SFTPAttributes.from_stat(item.stat())
                attr.filename = item.name
                result.append(attr)
            return result
        except Exception as e:
            return self._err(e)

    def open(self, path, flags, attr):
        try:
            fd = os.open(self.real(path), flags, 0o600)
            if flags & os.O_RDWR:
                stream = os.fdopen(fd, "r+b", buffering=0)
            elif flags & os.O_WRONLY:
                stream = os.fdopen(fd, "wb", buffering=0)
            else:
                stream = os.fdopen(fd, "rb", buffering=0)
            handle = paramiko.SFTPHandle(flags)
            if not flags & os.O_WRONLY or flags & os.O_RDWR:
                handle.readfile = stream
            if flags & (os.O_WRONLY | os.O_RDWR):
                handle.writefile = stream
            return handle
        except Exception as e:
            return self._err(e)

    def mkdir(self, path, attr):
        try:
            # os.mkdir MUST fail when already present; tests lock isolation.
            os.mkdir(self.real(path))
            return paramiko.SFTP_OK
        except Exception as e:
            return self._err(e)

    def rmdir(self, path):
        try:
            os.rmdir(self.real(path))
            return paramiko.SFTP_OK
        except Exception as e:
            return self._err(e)

    def remove(self, path):
        try:
            os.remove(self.real(path))
            return paramiko.SFTP_OK
        except Exception as e:
            return self._err(e)

def serve(conn, key):
    transport = paramiko.Transport(conn)
    try:
        transport.add_server_key(key)
        transport.set_subsystem_handler("sftp", paramiko.SFTPServer, Files)
        transport.start_server(server=Auth())
        while transport.is_active():
            time.sleep(0.1)
    except Exception as exc:
        print("SFTP_LAB_CLIENT_CLOSED", type(exc).__name__, flush=True)
    finally:
        transport.close()

server_key = paramiko.RSAKey.generate(2048)
with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("127.0.0.1", 21945))
    listener.listen(20)
    print("LOCAL_SFTP_EXCLUSIVE_MKDIR_READY", flush=True)
    while True:
        client, _ = listener.accept()
        threading.Thread(target=serve, args=(client, server_key), daemon=True).start()
