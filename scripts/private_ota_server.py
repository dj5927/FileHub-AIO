"""Private Direct OTA origin. Run behind a trusted HTTPS reverse proxy.
No APK signing keys, GitHub tokens, or user credentials are stored in source.
"""
import argparse
import hashlib
import hmac
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import re
import secrets
import shutil
from urllib.parse import urlsplit

VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+")
TOKEN = re.compile(r"[A-Za-z0-9_-]{32,128}")
HEX = re.compile(r"[0-9a-f]{64}")

def digest(file):
    with file.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def read_manifest(root):
    data=(root/"manifest.json").read_bytes()
    if len(data)>250000: raise ValueError("Manifest too large")
    m=json.loads(data)
    version=m["versionName"]
    if m["channel"]!="direct" or m["package"]!="com.nasmount.mobile" or not VERSION.fullmatch(version):
        raise ValueError("Wrong release channel")
    if type(m["versionCode"]) is not int or m["versionCode"]<1:
        raise ValueError("Invalid version code")
    name="FileHub-AIO-v"+version+".apk"
    if m["filename"]!=name or not HEX.fullmatch(m["sha256"]):
        raise ValueError("Invalid APK metadata")
    apk=root/name
    if apk.is_symlink() or not apk.is_file() or apk.stat().st_size!=m["size"] or not 100000<=m["size"]<=500000000:
        raise ValueError("Invalid APK file")
    srcname="FileHub-AIO-Direct-v"+version+"-source.zip"
    if m["sourceFilename"]!=srcname:
        raise ValueError("Corresponding source archive required")
    source=root/srcname
    if source.is_symlink() or not source.is_file() or source.stat().st_size<=0:
        raise ValueError("Missing corresponding source")
    return data,m,apk,source

def authorized(header,token_file):
    if not header or not header.startswith("Bearer "): return False
    token=header[7:]
    if not TOKEN.fullmatch(token): return False
    hashed=hashlib.sha256(token.encode("ascii")).hexdigest()
    try:
        values=json.loads(token_file.read_text(encoding="utf-8"))
        if not isinstance(values,list) or not values:return False
        if any(not isinstance(v,str) or not HEX.fullmatch(v) for v in values):return False
        return any(hmac.compare_digest(hashed,v) for v in values)
    except (OSError,ValueError,TypeError): return False

def handler(root,token_file,prefix):
    class Handler(BaseHTTPRequestHandler):
        # Never log Authorization, query string, or any secret.
        def log_message(self,fmt,*args): pass
        def headers_common(self,status,mime,length):
            self.send_response(status)
            self.send_header("Content-Type",mime)
            self.send_header("Content-Length",str(length))
            self.send_header("Cache-Control","private, no-store")
            self.send_header("X-Content-Type-Options","nosniff")
            self.end_headers()
        def reject(self,status):
            self.headers_common(status,"text/plain",0)
        def do_GET(self):
            if not authorized(self.headers.get("Authorization"),token_file):
                self.reject(403);return
            parsed=urlsplit(self.path)
            if parsed.query or parsed.fragment or not parsed.path.startswith(prefix):
                self.reject(404);return
            try:
                data,m,apk,src=read_manifest(root)
                path=parsed.path[len(prefix):]
                if path=="manifest.json":
                    self.headers_common(200,"application/json",len(data));self.wfile.write(data)
                elif path=="download/"+m["filename"] or path=="source/"+m["sourceFilename"]:
                    file=apk if path.startswith("download/") else src
                    with file.open("rb") as stream:
                        self.headers_common(200,"application/octet-stream",file.stat().st_size)
                        shutil.copyfileobj(stream,self.wfile,64*1024)
                else:self.reject(404)
            except (OSError,ValueError,KeyError,TypeError):
                # Do not expose server filesystem paths or error details.
                try:self.reject(503)
                except OSError:pass
    return Handler

def main():
    parser=argparse.ArgumentParser()
    sub=parser.add_subparsers(dest="command",required=True)
    sub.add_parser("token")
    serve=sub.add_parser("serve")
    serve.add_argument("--root",required=True,type=Path)
    serve.add_argument("--tokens",required=True,type=Path)
    serve.add_argument("--port",type=int,default=18765)
    serve.add_argument("--prefix",default="/filehub/direct/")
    args=parser.parse_args()
    if args.command=="token":
        token=secrets.token_urlsafe(32)
        print("Access code (share privately):",token)
        print("SHA256 (store in token list):",hashlib.sha256(token.encode()).hexdigest())
        return
    root=args.root.resolve(); token_file=args.tokens.resolve()
    if not args.prefix.startswith("/") or not args.prefix.endswith("/") or ".." in args.prefix:
        raise SystemExit("Invalid prefix")
    data,m,apk,src=read_manifest(root)
    if digest(apk)!=m["sha256"]: raise SystemExit("APK checksum mismatch")
    if not token_file.is_file(): raise SystemExit("Missing token hash list")
    print("Private OTA origin listening on loopback; configure HTTPS reverse proxy.",flush=True)
    ThreadingHTTPServer(("127.0.0.1",args.port),handler(root,token_file,args.prefix)).serve_forever()

if __name__=="__main__":main()
