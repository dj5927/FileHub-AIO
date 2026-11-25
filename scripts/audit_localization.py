"""Audit and migrate reviewed UI literals. No user data or protocol keys."""
import json,re,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
CAT=ROOT/"app/src/main/assets/locale/ui.tsv"
rows={}
for line in CAT.read_text(encoding="utf-8").splitlines():
    if not line or line.startswith("#"):continue
    fields=line.split("\t")
    assert len(fields)==4 and all(fields),line
    assert fields[0] not in rows,fields[0]
    rows[fields[0]]=fields[1:]
lit=r'"(?:\\.|[^"\\\r\n])*"'
lit_re=re.compile(lit)
hangul=re.compile(r"[가-힣]")
def key(raw):
    try:return json.loads(raw).replace("\n",r"\n").replace("\r",r"\r")
    except Exception:return raw[1:-1]
def code_mask(s):
    pat=re.compile(r'//[^\r\n]*|/\*[\s\S]*?\*/|'+lit+r"|'(?:\\.|[^'\\\r\n])*'")
    mask=list(s)
    for m in pat.finditer(s):mask[m.start():m.end()]=" "*(m.end()-m.start())
    return "".join(mask)
calls={"setTitle":(0,), "setMessage":(0,), "setPositiveButton":(0,), "setNegativeButton":(0,),
    "setNeutralButton":(0,), "setHint":(0,), "setText":(0,), "setContentDescription":(0,),
    "setTooltipText":(0,), "toast":(0,), "showError":(0,), "runJob":(0,),
    "section":(0,1), "action":(0,1), "label":(0,), "note":(0,), "button":(0,),
    "drawerLink":(1,), "drawerSection":(1,), "tile":(1,4), "readerToggle":(0,1),
    "readerSelect":(0,), "select":(0,), "number":(0,1), "toggle":(0,1),
    "text":(1,), "makeText":(1,), "createChooser":(1,), "setDialogTitle":(0,),
    "setSummary":(0,), "setButton":(1,), "heading":(0,), "muted":(0,),
    "showStatus":(0,), "render":(0,), "row":(1,2), "t":(0,)}
arrays={"setItems":0,"setSingleChoiceItems":0,"setMultiChoiceItems":0,"select":1,"readerSelect":1}
def args_at(s,mask,start):
    depth=0;begin=start;out=[]
    for i in range(start,len(s)):
        c=mask[i]
        if c in "([{":depth+=1
        elif c in ")]}":
            if c==")" and depth==0:
                out.append((begin,i));return out
            depth-=1
        elif c=="," and depth==0:out.append((begin,i));begin=i+1
    return out
missing={}
seen=set()
changes=0
apply="--apply" in sys.argv
for edition in ("main","direct","play"):
    for f in (ROOT/f"app/src/{edition}/java/com/nasmount/mobile").glob("*.java"):
        if f.name in {"L10n.java"}:continue
        s=f.read_text(encoding="utf-8-sig");mask=code_mask(s);edits={}
        def mark(a,b,value,wrapped=False):
            k=key(value)
            if not hangul.search(k):return
            seen.add(k)
            if k not in rows:
                missing.setdefault(k,set()).add(f.name)
            elif not wrapped:
                edits[(a,b)]="L10n.t("+value+")"
        for m in re.finditer(r'\b('+"|".join(sorted(set(calls)|set(arrays)))+r')\s*\(',mask):
            name=m.group(1)
            if name=="t" and not mask[max(0,m.start()-5):m.start()].endswith("L10n."):continue
            if name in ("render","row","heading","muted","tile","drawerSection","drawerLink") and f.name!="MainActivity.java":continue
            args=args_at(s,mask,m.end());indices=calls.get(name,())
            if name=="action" and mask[max(0,m.start()-3):m.start()]=="Ui.":indices=(1,)
            for idx in indices:
                if idx>=len(args):continue
                a,b=args[idx];raw=s[a:b];match=re.match(r'\s*('+lit+r')',raw)
                if match:
                    following=raw[match.end(1):].lstrip()
                    if not following or following.startswith("+"):
                        mark(a+match.start(1),a+match.end(1),match.group(1),name=="t")
            idx=arrays.get(name,-1)
            if idx>=0 and idx<len(args):
                a,b=args[idx];raw=s[a:b]
                if re.match(r'\s*new\s+String\s*\[\s*\]\s*\{',raw):
                    for match in lit_re.finditer(raw):
                        mark(a+match.start(),a+match.end(),match.group(0),
                             raw[max(0,match.start()-7):match.start()].endswith("L10n.t("))
        if apply and edits:
            for (a,b),v in sorted(edits.items(),reverse=True):s=s[:a]+v+s[b:]
            f.write_text(s,encoding="utf-8",newline="\n")
            changes+=len(edits)
print(f"CATALOG={len(rows)} UI_KEYS={len(seen)} MISSING={len(missing)} WRAPPED={changes}")
if "--all" in sys.argv:
    for k,files in sorted(missing.items(),key=lambda kv:(0 if 'SettingsActivity.java' in kv[1] else 1 if 'MainActivity.java' in kv[1] else 2, sorted(kv[1])[0], kv[0])):
        print(k+" | "+",".join(sorted(files)))
