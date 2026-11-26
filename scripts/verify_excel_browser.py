"""Offline Chromium regression: actual Office host, XLSX, find and frozen headers."""
from pathlib import Path
import json, time, subprocess, tempfile, threading, urllib.request, io, os
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from urllib.parse import urlsplit
import websocket
import openpyxl
ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'app/src/main/assets/office_hwp'
def fixture():
    w=openpyxl.Workbook(); a=w.active; a.title='First'
    a['B2']='Needle first'; a['T180']='NEEDLE target'; a['F12']=1234.5
    a['F12'].number_format='#,##0.00'; a['G13']='=SUM(1,2)'
    a['Z200']='End'; w.create_sheet('Second')['A1']='Other sheet only'
    b=io.BytesIO(); w.save(b); return b.getvalue()
XLSX=fixture()
class Handler(SimpleHTTPRequestHandler):
    def log_message(self,*args): pass
    def translate_path(self,path):
        path=urlsplit(path).path
        if path.startswith(('/tests/','/app/')): return str(ROOT/path.lstrip('/'))
        return str(ASSETS/path.lstrip('/'))
    def do_GET(self):
        if self.path=='/office-input':
            self.send_response(200);self.send_header('Content-Type','application/octet-stream')
            self.send_header('Content-Length',str(len(XLSX)));self.end_headers()
            self.wfile.write(XLSX)
        else: super().do_GET()
def wait(fn,seconds=20):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        try:
            value=fn()
            if value:return value
        except (OSError,ValueError,IndexError):pass
        time.sleep(.15)
    raise AssertionError('Timed out: '+str(fn))
def main():
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
    threading.Thread(target=server.serve_forever,daemon=True).start()
    url='http://127.0.0.1:'+str(server.server_port)
    browser=Path(r'C:\Program Files\Google\Chrome\Application\chrome.exe')
    if not browser.exists():browser=Path(r'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe')
    with tempfile.TemporaryDirectory(prefix='filehub-browser-qa-') as d:
        proc=subprocess.Popen([str(browser),'--headless=new','--disable-gpu','--no-first-run',
            '--remote-debugging-port=0','--remote-allow-origins=*','--user-data-dir='+d,
            '--window-size=430,850','about:blank'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        ws=None
        try:
            port=wait(lambda:(Path(d)/'DevToolsActivePort').read_text().splitlines()[0])
            tabs=wait(lambda:json.load(urllib.request.urlopen('http://127.0.0.1:'+port+'/json/list',timeout=3)))
            ws=websocket.create_connection(tabs[0]['webSocketDebuggerUrl'],timeout=30,suppress_origin=True)
            seq=0
            def call(method,params=None):
                nonlocal seq
                seq+=1;ident=seq
                ws.send(json.dumps(dict(id=ident,method=method,params=params or {})))
                while True:
                    msg=json.loads(ws.recv())
                    if msg.get('id')==ident:
                        if 'error' in msg:raise RuntimeError(msg['error'])
                        return msg.get('result',{})
            def ev(expression):
                result=call('Runtime.evaluate',dict(expression=expression,returnByValue=True,awaitPromise=True))
                if 'exceptionDetails' in result:
                    raise AssertionError(result['exceptionDetails'])
                return result['result'].get('value')
            call('Page.enable')
            call('Emulation.setDeviceMetricsOverride',dict(width=412,height=780,deviceScaleFactor=1,mobile=True))
            call('Page.addScriptToEvaluateOnNewDocument',dict(source="""window.NasOfficeBridge={
              getConfiguration:()=>JSON.stringify({name:'fixture.xlsx',mode:'sheet',token:'test',readOnly:true}),
              sheetReady:()=>window.qaReady=true
            };"""))
            for rel in ['tests/android_excel_v115/test-office-v115.html','tests/android_excel_v116/test-pinch-v116.html',
                        'tests/android_excel_v116/test-images-overflow-v116.html','tests/android_excel_v116/test-style-property-v116.html']:
                call('Page.navigate',dict(url=url+'/'+rel))
                result=wait(lambda:ev("document.getElementById('result')?.textContent?.match(/^(PASS|FAIL)/)?document.getElementById('result').textContent:null"),30)
                print(rel,result,flush=True)
                assert result.startswith('PASS'),result
            call('Page.navigate',dict(url=url+'/office-host.html?lang=en'))
            wait(lambda:ev('window.qaReady===true'),30)
            result=ev("""(async()=>{
              const $=id=>document.getElementById(id);
              const assert=(v,m)=>{if(!v)throw Error(m)};
              const frame=()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
              const sc=$('grid-scroll'),surface=$('sheet-surface');
              assert($('formula-input').readOnly,'read-only fixture');
              const stable=()=>{const r=sc.getBoundingClientRect(),a=sc.querySelector('.excel-row-layer').getBoundingClientRect(),
                b=sc.querySelector('.excel-col-layer').getBoundingClientRect(),c=sc.querySelector('.excel-corner').getBoundingClientRect();
                assert(Math.abs(a.left-r.left)<1.5,'row header drift '+JSON.stringify({a:a.left,r:r.left}));
                assert(Math.abs(b.top-r.top)<1.5,'column header drift');
                assert(Math.abs(c.left-r.left)<1.5&&Math.abs(c.top-r.top)<1.5,'corner drift');};
              sc.scrollTop=300;await frame();const row=sc.querySelector('.excel-row-header');
              for(const x of [200,600,90,1100,0]){sc.scrollLeft=x;await frame();stable();assert(row.isConnected,'row header replaced on horizontal scroll')}
              for(let i=0;i<18;i++)sc.dispatchEvent(new WheelEvent('wheel',{ctrlKey:true,deltaY:100,clientX:210,clientY:350,cancelable:true}));
              await frame();assert(Math.abs(Number(surface.style.zoom)-.3)<.001,'30 percent zoom not reached');stable();
              sc.scrollTop=450;sc.scrollLeft=140;await frame();stable();
              for(let i=0;i<18;i++)sc.dispatchEvent(new WheelEvent('wheel',{ctrlKey:true,deltaY:-100,clientX:210,clientY:350,cancelable:true}));
              await frame();stable();
              window.OfficeRequestSearch();
              const q=$('sheet-find-query'), info=$('sheet-find-info'), confirm=$('sheet-find-confirm');
              const search=async text=>{q.value=text;q.dispatchEvent(new Event('input'));await new Promise(r=>setTimeout(r,180));};
              assert($('sheet-find-dialog').open,'find dialog did not open');
              await search('needle');assert(info.textContent.includes('B2')&&info.textContent.includes('1 / 2'),'first match '+info.textContent);
              $('sheet-find-next').click();assert(info.textContent.includes('T180'),'next match '+info.textContent);
              confirm.click();await frame();
              assert(!$('sheet-find-dialog').open,'confirm did not close');
              assert($('cell-position').value==='T180','jump selected wrong cell');
              const target=sc.querySelector('[data-address="T180"]'), box=target?.getBoundingClientRect(),viewport=sc.getBoundingClientRect();
              assert(target?.classList.contains('selected'),'target not selected');
              assert(box.left>=viewport.left-1&&box.top>=viewport.top-1&&box.left<viewport.right&&box.top<viewport.bottom,'target outside viewport');
              stable();
              window.OfficeRequestSearch();await search('1,234.50');assert(info.textContent.includes('F12'),'formatted number not found');
              await search('sum(1,2)');assert(info.textContent.includes('G13'),'formula not found');
              await search('Other sheet only');assert(confirm.disabled,'searched other sheet');
              window.OfficeDismissSearch();
              $('sheet-tabs').children[1].click();window.OfficeRequestSearch();await search('Other sheet only');
              assert(info.textContent.includes('A1')&&!confirm.disabled,'second sheet not found');
              await search('needle');assert(confirm.disabled,'cross-sheet match leaked');
              window.OfficeDismissSearch();
              return 'PASS: actual host find, case folding, next/confirm jump, formatted values, formulas, read-only, sheet isolation, sticky headers and 30% zoom';
            })()""")
            print(result,flush=True)
        finally:
            if ws:ws.close()
            proc.terminate()
            try:proc.wait(8)
            except subprocess.TimeoutExpired:proc.kill();proc.wait()
            server.shutdown();server.server_close()
if __name__=='__main__':main()
