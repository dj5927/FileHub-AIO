// Run against the packaged asset tree, not an upstream development server.
// Usage: node tests/office_dependency_refresh.mjs <puppeteer-core-dir> <sample-dir>
import assert from 'node:assert/strict';
import http from 'node:http';
import path from 'node:path';
import { readFile, stat } from 'node:fs/promises';
import { createRequire } from 'node:module';

const repo = path.resolve(import.meta.dirname, '..');
const assets = path.join(repo, 'app/src/main/assets/office_hwp');
const require = createRequire(path.join(path.resolve(process.argv[2]), 'package.json'));
const puppeteer = require(path.resolve(process.argv[2]));
const samples = path.resolve(process.argv[3]);
const chrome = process.env.FILEHUB_CHROME ||
  'C:/Program Files/Google/Chrome/Application/chrome.exe';
const mime = {'.html':'text/html; charset=utf-8', '.js':'application/javascript',
  '.mjs':'application/javascript', '.css':'text/css', '.wasm':'application/wasm',
  '.svg':'image/svg+xml', '.png':'image/png', '.ico':'image/x-icon',
  '.woff2':'font/woff2', '.json':'application/json'};
let input;
const server = http.createServer(async (req,res)=>{
  try {
    const url = new URL(req.url,'http://localhost');
    const route = decodeURIComponent(url.pathname);
    if (route === '/harness') {
      res.setHeader('Content-Type','text/html; charset=utf-8');
      res.end('<!doctype html><html><body><div id="editor" style="height:95vh"></div></body></html>');
      return;
    }
    if (route === '/office-input') {
      res.setHeader('Content-Type','application/octet-stream');res.end(input);return;
    }
    let folder = assets, rel = route.slice(1);
    if (route.startsWith('/tests/') || route.startsWith('/app/')) folder = repo;
    if (route === '/wasm/rhwp_bg.wasm') rel = 'assets/rhwp_bg-CjDYAjgF.wasm';
    if (route.startsWith('/fixtures/')) {folder=samples;rel=route.slice(10);}
    const file = path.resolve(folder,rel);
    if (!file.startsWith(folder+path.sep) || !(await stat(file)).isFile()) throw Error('not found');
    res.setHeader('Content-Type',mime[path.extname(file)] || 'application/octet-stream');
    res.end(await readFile(file));
  } catch {
    res.writeHead(404);res.end('Not found');
  }
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const origin = 'http://127.0.0.1:'+server.address().port;
const browser = await puppeteer.launch({executablePath:chrome,headless:true,
  args:['--disable-background-networking','--disable-gpu','--no-first-run']});
try {
  async function makePage() {
    const page=await browser.newPage();
    await page.setViewport({width:420,height:800,isMobile:true,hasTouch:true});
    await page.setRequestInterception(true);
    page.on('request',r=>{if (r.url().startsWith(origin+'/') || /^(data|blob|about):/.test(r.url())) r.continue();else r.abort();});
    page.on('pageerror',e=>console.log('PAGE_ERROR',e.message));
    return page;
  }
  for(const file of ['android_excel_v115/test-office-v115.html',
    'android_excel_v116/test-images-overflow-v116.html',
    'android_excel_v116/test-pinch-v116.html',
    'android_excel_v116/test-style-property-v116.html']) {
    const page=await makePage();
    await page.goto(origin+'/tests/'+file,{waitUntil:'domcontentloaded'});
    await page.waitForFunction(()=>/^(PASS|FAIL)/.test(document.querySelector('#result')?.textContent||''),{timeout:45000});
    const result=await page.$eval('#result',e=>e.textContent);
    assert(result.startsWith('PASS'),file+': '+result);
    console.log('EXCEL_PASS',file,result);
    await page.close();
  }
  for (const sample of ['shift-return.hwp','form-002.hwpx']) {
    input=await readFile(path.join(samples,sample));
    const page=await makePage();
    const fmt=sample.endsWith('.hwpx')?'hwpx':'hwp';
    await page.goto(origin+'/harness');
    const result=await page.evaluate(async ({sample,fmt})=>{
      const {createEditor}=await import('/sdk/index.js');
      const ed=await createEditor('#editor',{studioUrl:location.origin+'/viewer.html',
        renderer:'canvas2d',handshakeTimeoutMs:10000,requestTimeoutMs:45000});
      const data=new Uint8Array(await (await fetch('/office-input')).arrayBuffer());
      await ed.loadFile(data,sample,{suppressDialogs:true});
      const before=await ed.getDocumentState();
      if(before.format!==fmt || before.pageCount<1) throw Error('Unexpected format/page count');
      const svg=await ed.getPageSvg(0);
      if(!svg.includes('<svg')||svg.length<100)throw Error('No SVG rendering');
      window.__testEditor=ed;
      window.__testState=before;
      window.__testSvg=svg;
      return {format:fmt,pages:before.pageCount,svgLength:svg.length};
    },{sample,fmt});
    const frame=await (await page.$('#editor iframe')).contentFrame();
    await frame.waitForSelector('#scroll-container canvas',{timeout:15000});
    const canvas=await frame.$('#scroll-container canvas');
    const box=await canvas.boundingBox();
    await page.mouse.click(box.x+Math.min(box.width/2,120),box.y+Math.min(box.height/3,140));
    await frame.evaluate(()=>{
      // The real editor owns this offscreen input. Dev-only globals are
      // deliberately absent from the production bundle.
      const inputs=[...document.querySelectorAll('textarea[aria-label]')];
      const input=inputs.find(e=>e.style.opacity==='0' && e.style.left==='-9999px');
      if(!input)throw Error('Editor text input missing');
      input.focus();
    });
    await page.keyboard.type('QA118',{delay:20});
    await page.waitForFunction(async ()=>{
      const state=await window.__testEditor.getDocumentState();
      return state.dirty&&state.documentSha256!==window.__testState.documentSha256;
    },{timeout:15000});
    console.log('RHWP_NATIVE_EDIT_PASS',sample);
    const roundtrip=await page.evaluate(async ({sample,fmt,edited})=>{
      const ed=window.__testEditor,before=window.__testState;
      const saved=fmt==='hwp'?await ed.exportHwp():await ed.exportHwpx();
      if(saved.length<100)throw Error('Empty HWP export');
      await ed.notifySaved(sample);
      await ed.loadFile(saved,sample,{suppressDialogs:true,skipUnsavedGuard:true});
      const after=await ed.getDocumentState();
      if(after.pageCount!==before.pageCount||after.format!==fmt)throw Error('Export/reopen changed format or page count');
      if(edited&&after.documentSha256===before.documentSha256)
        throw Error('Edited document reverted to original after save/reopen');
      return {format:fmt,pages:after.pageCount,exportBytes:saved.length,edited};
    },{sample,fmt,edited:true});
    console.log('RHWP_ROUNDTRIP_PASS',sample,JSON.stringify(roundtrip));
    await page.close();
    // Test FileHub's actual compact host and native save callback separately.
    const host=await makePage();
    await host.evaluateOnNewDocument(({sample})=>{
      let chunks=[],expected=0;
      window.__saveLength=0;
      window.NasOfficeBridge={
        getConfiguration:()=>JSON.stringify({token:'fixture',name:sample,mode:'hwp',readOnly:false}),
        beginSave:(token,len)=>{expected=len;chunks=[];return 'OK';},
        appendSave:(token,chunk)=>{chunks.push(atob(chunk));return 'OK';},
        finishSave:()=>{const size=chunks.reduce((a,b)=>a+b.length,0);if(size!==expected)return 'ERROR';
          window.__saveLength=size;setTimeout(()=>window.OfficeNativeSaveResult(true,'OK'),0);return 'OK';},
        cancelSave:()=>{chunks=[];}
      };
    },{sample});
    await host.goto(origin+'/office-host.html',{waitUntil:'domcontentloaded'});
    await host.waitForFunction(()=>document.querySelector('#hwp-container iframe')?.contentDocument?.body?.classList.contains('nas-compact'),{timeout:60000});
    await host.evaluate(()=>window.OfficeRequestSave());
    await host.waitForFunction(()=>window.__saveLength>100 && !document.querySelector('#save-original').disabled,{timeout:60000});
    assert(await host.evaluate(()=>!document.querySelector('#hwp-tool-toggle').hidden));
    await host.click('#hwp-tool-toggle');
    assert(await host.evaluate(()=>!document.querySelector('#hwp-container iframe').contentDocument.body.classList.contains('nas-compact')));
    console.log('RHWP_FILEHUB_HOST_SAVE_PASS',sample,await host.evaluate(()=>window.__saveLength));
    await host.close();
  }
  console.log('OFFICE_DEPENDENCY_REFRESH_PASS');
} finally {await browser.close();await new Promise(resolve=>server.close(resolve));}
