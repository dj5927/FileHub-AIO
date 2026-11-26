"""Publish a signed Direct APK and matching source to an existing private NAS root.
Run after signature/package/version verification and source archive creation.
Never copies access keys or exposes the private directory in the web root.
"""
import argparse, hashlib, json, os, re, shutil, zipfile
from pathlib import Path

def digest(p):
    with p.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def publish(root,apk,source,version,code,notes):
    if not re.fullmatch(r"[0-9]+[.][0-9]+[.][0-9]+",version) or code<1:
        raise ValueError("Invalid version")
    if not 100000<=apk.stat().st_size<=500000000:
        raise ValueError("Invalid APK size")
    if not (root/"access.sha256").is_file():
        raise ValueError("Missing server authorization configuration")
    with zipfile.ZipFile(source) as z:
        names=z.namelist()
        if not any(n.endswith("app/build.gradle") for n in names):
            raise ValueError("Missing project source")
        if any(n.lower().endswith((".apk",".aab",".jks",".p12",".keystore",".pem")) for n in names):
            raise ValueError("Unexpected private or binary file in source")
    filename=f"FileHub-AIO-v{version}.apk"
    sourcefilename=f"FileHub-AIO-Direct-v{version}-source.zip"
    for origin,name in [(apk,filename),(source,sourcefilename)]:
        temp=root/(name+".pending")
        shutil.copyfile(origin,temp)
        if digest(temp)!=digest(origin):
            temp.unlink(missing_ok=True)
            raise IOError("NAS copy checksum mismatch")
        os.replace(temp,root/name)
    manifest={"channel":"direct","package":"com.nasmount.mobile","versionName":version,
              "versionCode":code,"filename":filename,"size":apk.stat().st_size,
              "sha256":digest(apk),"sourceFilename":sourcefilename,"sourceSha256":digest(source),"notes":notes}
    old=root/"manifest.json"
    if old.exists() and json.loads(old.read_text(encoding="utf8"))["versionCode"]>code:
        raise ValueError("Cannot downgrade channel")
    temp=root/"manifest.json.pending"
    temp.write_text(json.dumps(manifest,ensure_ascii=False),encoding="utf8")
    os.replace(temp,old)
    return manifest

if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--root",type=Path,required=True)
    p.add_argument("--apk",type=Path,required=True)
    p.add_argument("--source",type=Path,required=True)
    p.add_argument("--version",required=True)
    p.add_argument("--code",type=int,required=True)
    p.add_argument("--notes",default="")
    a=p.parse_args()
    print(json.dumps(publish(a.root,a.apk,a.source,a.version,a.code,a.notes),ensure_ascii=True))
