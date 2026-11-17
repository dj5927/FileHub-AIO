"""Isolated Paramiko SFTP fixture with CP949 wire filename bytes."""
import errno
import os
import pathlib
import posixpath
import socket
import sys
import threading
import time

if len(sys.argv) != 3:
    raise SystemExit("Usage: sftp_charset_server.py <existing-pydeps> <QA-root>")
sys.path.insert(0, sys.argv[1])
import paramiko

root = pathlib.Path(sys.argv[2]).resolve()
if "QA_charset_1365" not in root.parts:
    raise SystemExit("Only QA_charset_1365 test root is allowed")
root.mkdir(parents=True, exist_ok=True)

# Standard Paramiko SFTP assumes UTF-8. This isolated server models a
# legacy SFTP peer that transmits raw CP949 path bytes instead.
def cp949_text(message):
    return message.get_string().decode("cp949", errors="strict")
paramiko.Message.get_text = cp949_text

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
    def target(self, path):
        normalized = posixpath.normpath("/" + path.lstrip("/"))
        path = (root / normalized.lstrip("/")).resolve()
        if path != root and root not in path.parents:
            raise PermissionError("Outside QA root")
        return path
    def canonicalize(self, path):
        # SSH_FXP_REALPATH/SSH_FXP_NAME must use the same CP949 encoding as
        # LISTDIR responses. The default Paramiko server uses UTF-8 here.
        return posixpath.normpath("/" + path.lstrip("/")).encode("cp949")
    @staticmethod
    def err(exc):
        return paramiko.SFTPServer.convert_errno(
            exc.errno if isinstance(exc, OSError) else errno.EIO)
    def stat(self, path):
        try:
            return paramiko.SFTPAttributes.from_stat(self.target(path).stat())
        except Exception as exc:
            return self.err(exc)
    def lstat(self, path):
        return self.stat(path)
    def list_folder(self, path):
        try:
            files = []
            for file in self.target(path).iterdir():
                attr = paramiko.SFTPAttributes.from_stat(file.stat())
                attr.filename = file.name.encode("cp949", errors="strict")
                files.append(attr)
            return files
        except Exception as exc:
            return self.err(exc)
    def open(self, path, flags, attr):
        try:
            fd = os.open(self.target(path), flags, 0o600)
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
        except Exception as exc:
            return self.err(exc)
    def mkdir(self, path, attr):
        try:
            os.mkdir(self.target(path))
            return paramiko.SFTP_OK
        except Exception as exc:
            return self.err(exc)
    def rmdir(self, path):
        try:
            os.rmdir(self.target(path))
            return paramiko.SFTP_OK
        except Exception as exc:
            return self.err(exc)
    def remove(self, path):
        try:
            os.remove(self.target(path))
            return paramiko.SFTP_OK
        except Exception as exc:
            return self.err(exc)
    def rename(self, oldpath, newpath):
        try:
            os.rename(self.target(oldpath), self.target(newpath))
            return paramiko.SFTP_OK
        except Exception as exc:
            return self.err(exc)

def serve(conn, key):
    transport = paramiko.Transport(conn)
    try:
        transport.add_server_key(key)
        transport.set_subsystem_handler("sftp", paramiko.SFTPServer, Files)
        transport.start_server(server=Auth())
        while transport.is_active():
            time.sleep(0.1)
    except Exception as exc:
        print("SFTP_LAB_CLIENT_ERROR", type(exc).__name__, flush=True)
    finally:
        transport.close()

server_key = paramiko.RSAKey.generate(2048)
with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("127.0.0.1", 22945))
    listener.listen(16)
    print("LOCAL_CP949_SFTP_READY", flush=True)
    while True:
        client, _ = listener.accept()
        threading.Thread(target=serve, args=(client, server_key), daemon=True).start()
