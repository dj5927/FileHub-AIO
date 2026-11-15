import { createEditor } from '/sdk/index.js';
import { openWord, exportWord, openSlides, exportSlides } from '/office-word-ppt.js';
import { installPinch } from '/office-gestures.js';

const bridge = window.NasOfficeBridge;
const statusEl = document.getElementById('status');
const saveButton = document.getElementById('save-original');
let cfg, hwpEditor, workbook, activeSheet = 0;
let rowStart = 0, colStart = 0, selectedAddress = 'A1', dirty = false, busy = false;
let pendingNotify = false;
let sheetZoom = 1;
let toastTimer;
const WINDOW_ROWS = 55, WINDOW_COLS = 12;

function status(text, error = false) {
  statusEl.textContent = String(text);
  statusEl.classList.toggle('error', error);
  statusEl.classList.add('show');
  clearTimeout(toastTimer);
  if(!error) toastTimer=setTimeout(()=>statusEl.classList.remove('show'),2300);
}
function setBusy(value) {
  busy = value;
  saveButton.disabled = value;
}
function inBounds(n) { return Math.max(0, Math.min(1_048_575, n|0)); }
function toCell(row, col) { return XLSX.utils.encode_cell({r:row,c:col}); }
function getSheet() { return workbook.Sheets[workbook.SheetNames[activeSheet]]; }
function selectCell(address) {
  selectedAddress = address.toUpperCase();
  const cell = getSheet()[selectedAddress];
  document.getElementById('cell-position').value = selectedAddress;
  document.getElementById('formula-input').value = !cell ? '' :
    cell.f ? '=' + cell.f : cell.v === undefined || cell.v === null ? '' : String(cell.v);
  document.querySelectorAll('td.selected').forEach(el => el.classList.remove('selected'));
  const chosen = document.querySelector('td[data-address="' + selectedAddress + '"]');
  if (chosen) chosen.classList.add('selected');
}
function applyCellEdit() {
  const address = document.getElementById('cell-position').value.trim().toUpperCase();
  let loc;
  try { loc = XLSX.utils.decode_cell(address); }
  catch { status('유효한 셀 주소를 입력해 주세요.', true); return; }
  if (!address.match(/^[A-Z]{1,3}[1-9][0-9]{0,6}$/) ||
      loc.r>1048575 || loc.c>16383) {
    status('셀 주소가 올바르지 않습니다.', true); return;
  }
  const sheet = getSheet();
  const value = document.getElementById('formula-input').value;
  const old = sheet[address] || {};
  if (value === '') {
    delete sheet[address];
  } else if (value.startsWith('=')) {
    sheet[address] = { ...old, t:'n', f:value.slice(1), v: undefined, w:undefined };
  } else if (/^[-+]?(?:[0-9]+(?:\.[0-9]+)?|\.[0-9]+)$/.test(value)) {
    sheet[address] = { ...old, t:'n', f:undefined, v:Number(value), w:undefined };
  } else {
    sheet[address] = { ...old, t:'s', f:undefined, v:value, w:undefined };
  }
  let range;
  try { range = XLSX.utils.decode_range(sheet['!ref'] || 'A1'); }
  catch { range = {s:{r:0,c:0},e:{r:0,c:0}}; }
  range.s.r = Math.min(range.s.r, loc.r); range.s.c = Math.min(range.s.c, loc.c);
  range.e.r = Math.max(range.e.r, loc.r); range.e.c = Math.max(range.e.c, loc.c);
  sheet['!ref'] = XLSX.utils.encode_range(range);
  dirty = true;
  selectCell(address);
  renderSheet();
  status('셀 변경사항이 있습니다. NAS 원본에 저장하려면 아래 저장 버튼을 누르세요.');
}
function renderSheet() {
  const sheet = getSheet();
  const table = document.getElementById('cells');
  table.replaceChildren();
  const head = document.createElement('tr');
  const corner = document.createElement('th'); corner.className='row-header';
  corner.textContent = '#'; head.appendChild(corner);
  for (let col=colStart; col<colStart+WINDOW_COLS; col++) {
    const th = document.createElement('th');
    th.textContent = XLSX.utils.encode_col(col); head.appendChild(th);
  }
  table.appendChild(head);
  for (let row=rowStart; row<rowStart+WINDOW_ROWS; row++) {
    const tr = document.createElement('tr');
    const marker = document.createElement('th');
    marker.className='row-header'; marker.textContent=String(row+1); tr.appendChild(marker);
    for (let col=colStart; col<colStart+WINDOW_COLS; col++) {
      const address=toCell(row,col), cell=sheet[address], td=document.createElement('td');
      td.className='cell' + (cell?.f?' formula':'') +
        (address===selectedAddress?' selected':'');
      td.dataset.address=address;
      td.textContent = !cell ? '' : cell.w ?? (cell.v ?? (cell.f ? '='+cell.f : ''));
      td.title = address;
      td.addEventListener('click',()=>selectCell(address));
      td.addEventListener('dblclick',()=>{
        selectCell(address);document.getElementById('formula-input').focus();
      });
      tr.appendChild(td);
    }
    table.appendChild(tr);
  }
  document.getElementById('window-info').textContent =
    toCell(rowStart,colStart)+' ~ '+toCell(rowStart+WINDOW_ROWS-1,colStart+WINDOW_COLS-1);
}
function jumpToAddress() {
  const address=document.getElementById('cell-position').value.trim().toUpperCase();
  if (!/^[A-Z]{1,3}[1-9][0-9]{0,6}$/.test(address)) {
    status('올바른 셀 주소를 입력해 주세요.',true);return;
  }
  let pos;
  try { pos=XLSX.utils.decode_cell(address); }
  catch { status('셀 주소 범위를 벗어났습니다.',true);return; }
  if (pos.r>1048575 || pos.c>16383) { status('엑셀 최대 범위를 벗어났습니다.',true);return; }
  rowStart = pos.r; colStart = pos.c; selectedAddress=address;
  renderSheet();selectCell(address);
}
function attachSheetEvents() {
  document.getElementById('cell-save').onclick=applyCellEdit;
  document.getElementById('cell-jump').onclick=jumpToAddress;
  document.getElementById('formula-input').addEventListener('keydown',e=>{
    if(e.key==='Enter'){e.preventDefault();applyCellEdit();}
  });
  document.getElementById('sheet-select').onchange=e=>{
    activeSheet=Number(e.target.value);
    rowStart=0;colStart=0;selectedAddress='A1';renderSheet();selectCell('A1');
  };
  document.getElementById('row-prev').onclick=()=>{
    rowStart=Math.max(0,rowStart-WINDOW_ROWS);renderSheet();
  };
  document.getElementById('row-next').onclick=()=>{
    rowStart=inBounds(rowStart+WINDOW_ROWS);renderSheet();
  };
  document.getElementById('col-prev').onclick=()=>{
    colStart=Math.max(0,colStart-WINDOW_COLS);renderSheet();
  };
  document.getElementById('col-next').onclick=()=>{
    colStart=Math.min(16383,colStart+WINDOW_COLS);renderSheet();
  };
}
async function openSpreadsheet(bytes) {
  if (!window.XLSX) throw Error('내장 엑셀 파서를 불러오지 못했습니다.');
  workbook=XLSX.read(bytes,{type:'array',cellStyles:true,cellFormula:true,
    cellDates:true,cellNF:true,bookVBA:true});
  if (!workbook.SheetNames?.length) throw Error('시트가 없는 문서입니다.');
  const selector=document.getElementById('sheet-select');
  for(let i=0;i<workbook.SheetNames.length;i++){
    const option=document.createElement('option');
    option.value=String(i);option.textContent=workbook.SheetNames[i];
    selector.appendChild(option);
  }
  document.getElementById('spreadsheet-shell').hidden=false;
  const grid=document.getElementById('grid-scroll');
  installPinch(grid,()=>sheetZoom,scale=>{
    sheetZoom=scale;
    document.getElementById('cells').style.zoom=String(scale);
  },0.6,3);
  document.getElementById('sheet-tool-toggle').hidden=false;
  document.getElementById('sheet-tool-toggle').onclick=()=>{
    const expanded=document.getElementById('spreadsheet-shell').classList.toggle('expanded');
    document.getElementById('sheet-tool-toggle').textContent=expanded?'접기':'⋯';
  };
  attachSheetEvents();renderSheet();selectCell('A1');
  status('내장 스프레드시트 편집기 준비 완료 · '+workbook.SheetNames.length+'개 시트');
}
async function openHwp(bytes) {
  document.getElementById('hwp-shell').hidden=false;
  status('내장 HWP/HWPX 편집 엔진을 초기화하고 있습니다...');
  hwpEditor=await createEditor('#hwp-container',{
    studioUrl:location.origin+'/viewer.html',
    requestTimeoutMs:120000,handshakeTimeoutMs:6000,renderer:'canvas2d',
  });
  const loaded=await hwpEditor.loadFile(bytes,cfg.name,{suppressDialogs:true});
  // Same-origin bundled rhwp iframe. Keep its menu bar accessible, but
  // collapse desktop ribbons and rulers unless the user opens them.
  const frame=hwpEditor._iframe;
  if(frame?.contentDocument) {
    const doc=frame.contentDocument;
    const style=doc.createElement('style');
    style.textContent=[
      'body.nas-compact #studio-header > :not(#menu-bar) { display:none !important; }',
      'body.nas-compact #editor-area { grid-template-columns:0px minmax(0,1fr) auto !important;grid-template-rows:0px minmax(0,1fr) !important; }',
      'body.nas-compact #ruler-corner,body.nas-compact #h-ruler,body.nas-compact #v-ruler {display:none !important;}',
      'body.nas-compact #scroll-container {grid-area:2/2 !important;}',
      '#menu-bar {overflow-x:auto;scrollbar-width:none;}',
    ].join('\n');
    doc.head.appendChild(style);
    doc.body.classList.add('nas-compact');
    const toggle=document.getElementById('hwp-tool-toggle');
    toggle.hidden=false;
    toggle.onclick=()=>{
      const compact=doc.body.classList.toggle('nas-compact');
      toggle.textContent=compact?'도구':'도구 접기';
    };
    // The Studio already implements text layout zoom commands. Dispatch
    // actual zoom actions rather than applying CSS scale to the editor
    // (CSS scaling would desync the caret and its hit-testing).
    let hwpZoom=1;
    installPinch(doc.getElementById('scroll-container')||doc.body,
      ()=>hwpZoom,scale=>{
        if(Math.abs(scale-hwpZoom)<0.10) return;
        const direction=scale>hwpZoom?'sb-zoom-in':'sb-zoom-out';
        const button=doc.getElementById(direction);
        if(button) {
          button.click();
          hwpZoom=scale;
        }
      },0.6,3);
  }
  status('내장 HWP 편집기 준비 완료 · ' + (loaded?.pageCount || '?') +
    '쪽 · 편집 후 아래 원본 저장을 누르세요.');
}
function toBase64(bytes) {
  const piece=8192;let binary='';
  for(let i=0;i<bytes.length;i+=piece)
    binary+=String.fromCharCode(...bytes.subarray(i,i+piece));
  return btoa(binary);
}
async function persist(bytes) {
  const limit=64*1024*1024;
  if (bytes.byteLength>limit) throw Error('저장할 문서가 64MB 제한을 초과합니다.');
  if (bridge.beginSave(cfg.token,bytes.byteLength)!=='OK')
    throw Error('저장 작업을 시작하지 못했습니다.');
  try {
    for (let i=0;i<bytes.length;i+=16384) {
      const chunk=toBase64(bytes.subarray(i,i+16384));
      if (bridge.appendSave(cfg.token,chunk)!=='OK')
        throw Error('문서 저장 데이터 전송에 실패했습니다.');
    }
    if (bridge.finishSave(cfg.token)!=='OK')
      throw Error('문서 저장을 확정하지 못했습니다.');
  } catch (e) {
    bridge.cancelSave(cfg.token);
    throw e;
  }
}
async function saveDocument() {
  if (busy) return;
  setBusy(true);status('문서를 생성해 NAS Mount에 전달하고 있습니다...');
  try {
    let bytes;
    if (cfg.mode==='hwp') {
      bytes=cfg.name.toLowerCase().endsWith('.hwpx') ?
        await hwpEditor.exportHwpx() : await hwpEditor.exportHwp();
      pendingNotify=true;
    } else if(cfg.mode==='word') {
      bytes=await exportWord();
    } else if(cfg.mode==='slides') {
      bytes=await exportSlides();
    } else if(cfg.mode==='sheet') {
      // A user may tap bottom Save while still typing in the formula bar.
      // Apply that pending cell without requiring the separate cell button.
      const inputAddress=document.getElementById('cell-position').value.trim().toUpperCase();
      if(inputAddress===selectedAddress) {
        const cell=getSheet()[selectedAddress];
        const current=!cell ? '' :
          cell.f ? '='+cell.f : cell.v===undefined||cell.v===null ? '' : String(cell.v);
        if(document.getElementById('formula-input').value!==current)
          applyCellEdit();
      }
      const ext=cfg.name.toLowerCase();
      const type=ext.endsWith('.xls')?'biff8':ext.endsWith('.ods')?'ods':'xlsx';
      bytes=XLSX.write(workbook,{bookType:type,type:'array',compression:true,cellStyles:true});
    } else throw Error('지원하지 않는 문서 형식입니다.');
    if (!(bytes instanceof Uint8Array)) bytes=new Uint8Array(bytes);
    await persist(bytes);
    status('원본 파일에 저장 중입니다...');
  } catch (error) {
    status('저장 실패: '+(error?.message || error),true);setBusy(false);
  }
}
window.OfficeNativeSaveResult=async function(success,message){
  if(success){
    dirty=false;
    status('원본 파일에 저장했습니다.');
    if(pendingNotify&&hwpEditor) {
      try { await hwpEditor.notifySaved(); } catch(e) { console.warn('notifySaved',e); }
    }
  } else status('NAS 저장 실패: '+message,true);
  pendingNotify=false;setBusy(false);
};
saveButton.onclick=saveDocument;
window.addEventListener('error',e=>status('편집 엔진 오류: '+e.message,true));
window.addEventListener('unhandledrejection',e=>status(
  '편집 엔진 오류: '+(e.reason?.message || String(e.reason)),true));

async function start() {
  try {
    if (!bridge) throw Error('NAS Mount 문서 저장 연결이 없습니다.');
    cfg=JSON.parse(bridge.getConfiguration());
    const response=await fetch('/office-input',{cache:'no-store'});
    if(!response.ok) throw Error('원본 문서를 불러오지 못했습니다.');
    const bytes=new Uint8Array(await response.arrayBuffer());
    if (!bytes.length) throw Error('빈 문서는 열 수 없습니다.');
    if(cfg.mode==='hwp') await openHwp(bytes);
    else if(cfg.mode==='sheet') await openSpreadsheet(bytes);
    else if(cfg.mode==='word') await openWord(bytes,status);
    else if(cfg.mode==='slides') await openSlides(bytes,status);
    else if(cfg.mode==='legacy')
      throw Error('구형 .doc/.ppt는 아직 미지원입니다. DOCX 또는 PPTX로 변환 후 열어 주세요.');
    else throw Error('지원하지 않는 문서 형식입니다.');
  } catch(error) {
    status('문서를 열 수 없습니다: '+(error?.message || error),true);
    saveButton.disabled=true;
  }
}
start();
