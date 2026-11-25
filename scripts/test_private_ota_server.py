import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import unittest
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from http.server import ThreadingHTTPServer

spec=importlib.util.spec_from_file_location("ota",Path(__file__).with_name("private_ota_server.py"))
ota=importlib.util.module_from_spec(spec);spec.loader.exec_module(ota)

class PrivateOtaTest(unittest.TestCase):
    def test_authorization_and_exact_asset(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td);token="a"*43
            apk=root/"FileHub-AIO-v0.1.119.apk";apk.write_bytes(b"X"*100000)
            source=root/"FileHub-AIO-Direct-v0.1.119-source.zip";source.write_bytes(b"SOURCE")
            tokens=root/"tokens.json";tokens.write_text(json.dumps([hashlib.sha256(token.encode()).hexdigest()]))
            manifest={"channel":"direct","package":"com.nasmount.mobile","versionName":"0.1.119",
                "versionCode":120,"filename":apk.name,"size":apk.stat().st_size,
                "sha256":ota.digest(apk),"sourceFilename":source.name}
            (root/"manifest.json").write_text(json.dumps(manifest))
            server=ThreadingHTTPServer(("127.0.0.1",0),ota.handler(root,tokens,"/filehub/direct/"))
            worker=threading.Thread(target=server.serve_forever);worker.start()
            try:
                base=f"http://127.0.0.1:{server.server_port}/filehub/direct/"
                def get(path,access=None):
                    h={} if access is None else {"Authorization":"Bearer "+access}
                    with urlopen(Request(base+path,headers=h),timeout=3) as resp:return resp.read()
                for path in ("manifest.json","download/"+apk.name,"source/"+source.name):
                    with self.assertRaises(HTTPError) as error:get(path)
                    self.assertEqual(403,error.exception.code)
                    with self.assertRaises(HTTPError) as error:get(path,"b"*43)
                    self.assertEqual(403,error.exception.code)
                self.assertEqual(json.loads(get("manifest.json",token)),manifest)
                self.assertEqual(get("download/"+apk.name,token),apk.read_bytes())
                self.assertEqual(get("source/"+source.name,token),source.read_bytes())
                for invalid in ("tokens.json","download/../tokens.json","download/other.apk","?token="+token):
                    with self.assertRaises(HTTPError) as error:get(invalid,token)
                    self.assertEqual(404,error.exception.code)
                tokens.write_text("[]")
                with self.assertRaises(HTTPError) as error:get("manifest.json",token)
                self.assertEqual(403,error.exception.code)
            finally:server.shutdown();worker.join();server.server_close()

if __name__=="__main__":unittest.main()
