"""Migrate only authored UI literals with reviewed catalogue entries.
Never translate file names, remote paths, persisted enum values, or protocol data.
"""
import json,re
from pathlib import Path
root=Path(__file__).resolve().parents[1]
rows={}
for line in (root/"app/src/main/assets/locale/ui.tsv").read_text(encoding="utf-8").splitlines():
    if not line or line.startswith("#"):continue
    fields=line.split("\t")
    assert len(fields)==4 and all(fields),line
    assert fields[0] not in rows, fields[0]
    rows[fields[0]]=fields[1:]
lit=r'"(?:\\.|[^"\\\r\n])*"'
lit_re=re.compile(lit)
# Java literal unescaping for the reviewed simple catalogue.
def key(raw):
    try:return json.loads(raw).replace("\n",r"\n")
    except Exception:return raw[1:-1]
def wrap(raw):
    return "L10n.t("+raw+")" if key(raw) in rows else raw
# Skip comments and entire string/character literals so a pattern cannot
# rewrite examples inside comments or strings.
def code_mask(s):
    pattern=re.compile(r'//[^\r\n]*|/\*[\s\S]*?\*/|'+lit+r"|'(?:\\.|[^'\\\r\n])*'")
    mask=list(s)
    for m in pattern.finditer(s):
        mask[m.start():m.end()]=" "*(m.end()-m.start())
    return "".join(mask)
# These APIs display their direct literal arguments; no persisted labels.
calls={"setTitle":(0,), "setMessage":(0,), "setPositiveButton":(0,),
    "setNegativeButton":(0,), "setNeutralButton":(0,), "setHint":(0,),
    "setText":(0,), "setContentDescription":(0,), "setTooltipText":(0,),
    "toast":(0,), "showError":(0,), "runJob":(0,), "section":(0,1),
    "action":(0,1), "label":(0,), "note":(0,), "button":(0,),
    "drawerLink":(1,), "tile":(1,), "readerToggle":(0,1),
    "text":(1,), "makeText":(1,), "createChooser":(1,),
    "setDialogTitle":(0,), "setSummary":(0,), "setButton":(1,)}
def args_at(s,mask,start):
    depth=0
    begin=start
    out=[]
    for i in range(start,len(s)):
        c=mask[i]
        if c in "([{":depth+=1
        elif c in ")]}":
            if c==")" and depth==0:
                out.append((begin,i));return out
            depth-=1
        elif c=="," and depth==0:
            out.append((begin,i));begin=i+1
    return out
used=set()
changes=0
for edition in ("main","direct","play"):
    for f in (root/f"app/src/{edition}/java/com/nasmount/mobile").glob("*.java"):
        if f.name in {"L10n.java","ExternalApps.java","ExternalMediaProvider.java"}:continue
        s=f.read_text(encoding="utf-8-sig")
        mask=code_mask(s)
        edits={}
        for m in re.finditer(r'\b('+"|".join(calls)+r'|setItems|setSingleChoiceItems|setMultiChoiceItems)\s*\(',mask):
            name=m.group(1)
            args=args_at(s,mask,m.end())
            indices=calls.get(name,())
            # Special-case Ui.action(context,label). Other action() is
            # SettingsActivity.action(title,description,...).
            if name=="action" and mask[max(0,m.start()-3):m.start()]=="Ui.":indices=(1,)
            for idx in indices:
                if idx>=len(args):continue
                a,b=args[idx]
                raw=s[a:b]
                match=re.match(r'\s*('+lit+r')',raw)
                if match:
                    start=a+match.start(1);end=a+match.end(1)
                    value=match.group(1)
                    if key(value) in rows:
                        # Only translate literal expression/prefix, not a
                        # conditional boolean expression that compares text.
                        following=raw[match.end(1):].lstrip()
                        if not following or following.startswith("+"):
                            edits[(start,end)]=wrap(value);used.add(key(value))
            if name in {"setItems","setSingleChoiceItems","setMultiChoiceItems"} and args:
                a,b=args[0]
                raw=s[a:b]
                if re.match(r'\s*new\s+String\s*\[\s*\]\s*\{',raw):
                    # Only direct display arrays, never identifiers or user data.
                    for match in lit_re.finditer(raw):
                        value=match.group(0)
                        if key(value) in rows and not raw[max(0,match.start()-7):match.start()].endswith("L10n.t("):
                            edits[(a+match.start(),a+match.end())]=wrap(value);used.add(key(value))
        # Exception messages are presentation only; preserve all comparison
        # and protocol strings, and keep unknown messages unchanged.
        for m in re.finditer(r'\bnew\s+(?:IOException|IllegalArgumentException)\s*\(',mask):
            match=re.match(r'\s*('+lit+r')',s[m.end():])
            if match and key(match.group(1)) in rows:
                a=m.end()+match.start(1);b=m.end()+match.end(1)
                edits[(a,b)]=wrap(match.group(1));used.add(key(match.group(1)))
        if edits:
            for (a,b),value in sorted(edits.items(),reverse=True):s=s[:a]+value+s[b:]
            f.write_text(s,encoding="utf-8",newline="\n")
            changes+=len(edits)
print(f"LOCALIZED_UI_CALLS={changes} REVIEWED_KEYS_USED={len(used)} CATALOG_KEYS={len(rows)}")
