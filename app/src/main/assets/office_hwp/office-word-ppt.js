import { t } from '/office-locale.js';
import { PptxRenderer, PptxWriter } from '/pptx-engine/index.js';
import { installPinch, installSlideSwipe } from '/office-gestures.js';

// Everything is offline. Mutations are limited to paragraph/shape text.
// The original OOXML package, images and other parts are carried forward.
const $ = id => document.getElementById(id);
const W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main';
const P = 'http://schemas.openxmlformats.org/presentationml/2006/main';
const A = 'http://schemas.openxmlformats.org/drawingml/2006/main';
const MAX_XML_BYTES = 25 * 1024 * 1024;
const MAX_ZIP_UNCOMPRESSED = 384 * 1024 * 1024;

let wordZip, wordXml, wordNodes=[], wordOriginal, wordChanged=false;
let wordFitPage=true;
let pptRenderer, pptWriter, pptBytes, pptOriginal, pptIndex=0, pptShapes=[], pptChanged=false;
let modernPpt, slideView='modern', slideZoom=1, slideSvgUrl=null, drawing=0;
let notify = () => {};
let editing = false;

function update(text) { notify(text); }
function xmlText(doc) { return new XMLSerializer().serializeToString(doc); }
function parseXml(content) {
  const doc = new DOMParser().parseFromString(content,'application/xml');
  if (doc.querySelector('parsererror')) throw Error(t('문서 XML 형식을 읽을 수 없습니다.'));
  return doc;
}
function xmlSafeZip(zip) {
  let size=0, count=0;
  zip.forEach((name,entry)=>{
    if(++count>12000) throw Error(t('문서 내부 파일 개수 제한을 초과합니다.'));
    const uncompressed = entry._data?.uncompressedSize ?? 0;
    size += uncompressed;
    if(size>MAX_ZIP_UNCOMPRESSED) throw Error(t('문서 내부 데이터가 너무 큽니다.'));
    if(name.startsWith('/') || name.includes('../') || name.includes('..\\'))
      throw Error(t('문서 내부 경로가 올바르지 않습니다.'));
  });
}
function updateWordSelect() {
  const pick = $('word-paragraph');
  pick.replaceChildren();
  wordNodes.forEach((item,index)=>{
    if(!item.nodes.length) return;
    const option = document.createElement('option');
    option.value=String(index);
    option.textContent=(index+1)+' · '+(item.text||t('(빈 문단)')).slice(0,55);
    pick.appendChild(option);
  });
  if(pick.options.length) {
    pick.selectedIndex=0;
    onWordSelect();
  }
}
function onWordSelect() {
  const item=wordNodes[Number($('word-paragraph').value)];
  $('word-text').value=item?.text||'';
}
async function refreshWord() {
  const bytes = await wordZip.generateAsync({type:'uint8array',compression:'DEFLATE'});
  const root = $('word-preview');
  root.replaceChildren();
  await window.docx.renderAsync(bytes,root,undefined,{
    inWrapper:true,breakPages:true,renderHeaders:true,renderFooters:true,
    renderComments:false,renderAltChunks:false,ignoreFonts:true,
  });
  fitWordPages();
}
function fitWordPages() {
  const pages=Array.from(document.querySelectorAll('#word-preview section.docx'));
  const viewport=$('word-scroll').clientWidth-16;
  for(const page of pages) {
    page.style.zoom='1';
    const scale=Math.min(1,Math.max(.25,viewport/page.offsetWidth));
    page.style.zoom=wordFitPage?String(scale):'1';
  }
  $('word-scroll').style.overflowX=wordFitPage?'hidden':'auto';
  $('word-fit-toggle').textContent=wordFitPage?'100%':t('화면 맞춤');
}
export async function openWord(data, statusCallback) {
  notify=statusCallback;
  if(!window.JSZip || !window.docx?.renderAsync)
    throw Error(t('내장 Word 표시 라이브러리를 불러오지 못했습니다.'));
  wordOriginal=data;
  wordZip=await JSZip.loadAsync(data,{checkCRC32:true});
  xmlSafeZip(wordZip);
  const entry=wordZip.file('word/document.xml');
  if(!entry) throw Error(t('DOCX 내부 문서 본문이 없습니다.'));
  if((entry._data?.uncompressedSize??0)>MAX_XML_BYTES)
    throw Error(t('문서 본문이 편집 제한을 초과합니다.'));
  wordXml=parseXml(await entry.async('string'));
  const paragraphNodes=Array.from(wordXml.getElementsByTagNameNS(W,'p'));
  wordNodes=paragraphNodes.map(paragraph=>{
    const nodes=Array.from(paragraph.getElementsByTagNameNS(W,'t'));
    return {nodes,text:nodes.map(n=>n.textContent).join('')};
  });
  wordChanged=false;
  wordFitPage=true;
  $('word-shell').hidden=false;
  $('word-fit-toggle').onclick=()=>{
    wordFitPage=!wordFitPage;
    fitWordPages();
  };
  $('word-edit-toggle').onclick=()=>{$('word-editor').hidden=!$('word-editor').hidden;};
  $('word-close').onclick=()=>{$('word-editor').hidden=true;};
  $('word-paragraph').onchange=onWordSelect;
  $('word-apply').onclick=async()=>{
    if(editing) return;
    const index=Number($('word-paragraph').value),item=wordNodes[index];
    if(!item||!item.nodes.length) return;
    const value=$('word-text').value;
    if(value===item.text) { $('word-editor').hidden=true;return; }
    editing=true;
    try {
      // Only the selected paragraph is changed. Other XML parts stay intact.
      // The selected paragraph's first run keeps its font/paragraph styling.
      const first=item.nodes[0];
      first.textContent=value;
      first.setAttribute('xml:space','preserve');
      for(const remaining of item.nodes.slice(1)) remaining.textContent='';
      item.text=value;
      wordChanged=true;
      wordZip.file('word/document.xml',xmlText(wordXml));
      await refreshWord();
      update(t('문단 변경 완료 · 아래 원본 저장 버튼을 눌러 반영하세요.'));
      $('word-editor').hidden=true;
      updateWordSelect();
    } catch(err) {update(t('Word 문단 편집 실패: ')+err.message);}
    finally {editing=false;}
  };
  updateWordSelect();
  await refreshWord();
  update('Word 문서 준비 완료 · '+wordNodes.length+'개 문단');
}
export async function exportWord() {
  if(editing) throw Error(t('문단 편집을 마친 뒤 저장하세요.'));
  if(!wordChanged) return wordOriginal;
  return wordZip.generateAsync({type:'uint8array',compression:'DEFLATE'});
}

function shapesForSlide(index) {
  if (!pptRenderer || !pptRenderer.slidePaths[index]) return [];
  const raw=pptRenderer._files[pptRenderer.slidePaths[index]];
  if(!raw) return [];
  const doc=parseXml(new TextDecoder().decode(raw));
  const matches=[];
  for(const shape of Array.from(doc.getElementsByTagNameNS(P,'sp'))) {
    const tag=shape.getElementsByTagNameNS(P,'cNvPr')[0];
    const name=tag?.getAttribute('name');
    const text=Array.from(shape.getElementsByTagNameNS(A,'t'))
      .map(t=>t.textContent).join('');
    if(name && text.trim()) matches.push({name,text});
  }
  return matches;
}
function updateShapeSelect() {
  pptShapes=shapesForSlide(pptIndex);
  const select=$('slide-shape');
  select.replaceChildren();
  pptShapes.forEach((shape,i)=>{
    const opt=document.createElement('option');
    opt.value=String(i);
    opt.textContent=shape.name+' · '+shape.text.slice(0,32);
    select.appendChild(opt);
  });
  $('slide-apply').disabled=!pptShapes.length || !pptWriter;
  $('slide-text').disabled=!pptShapes.length;
  $('slide-text').value=pptShapes[0]?.text||'';
}
function slideCount() {
  return modernPpt?.slides?.length || pptRenderer?.slideCount || 0;
}
function applySlideZoom(scale) {
  slideZoom=Math.max(1,Math.min(4,scale));
  const stage=$('slide-stage'),content=$('slide-zoom-content');
  const width=Math.max(1,stage.clientWidth-10);
  content.style.width=Math.round(width*slideZoom)+'px';
  content.style.maxWidth='none';
  if(slideZoom<=1.02) stage.scrollLeft=0;
}
async function renderPptSlide() {
  const tick=++drawing;
  const img=$('slide-modern'),canvas=$('slide-canvas');
  if(slideSvgUrl){URL.revokeObjectURL(slideSvgUrl);slideSvgUrl=null;}
  if(slideView==='modern' && modernPpt) {
    try {
      const svg=await window.NasPptxModern.slideToSvgFile(
        modernPpt,modernPpt.slides[pptIndex]);
      if(tick!==drawing) return;
      if(typeof svg!=='string'||!svg.includes('<svg'))
        throw Error(t('SVG 출력이 없습니다.'));
      const url=URL.createObjectURL(new Blob([svg],{type:'image/svg+xml'}));
      await new Promise((resolve,reject)=>{
        img.onload=resolve;
        img.onerror=()=>reject(Error(t('SVG 이미지 로드 실패')));
        img.src=url;
      });
      if(tick!==drawing){URL.revokeObjectURL(url);return;}
      slideSvgUrl=url;
      canvas.hidden=true;
      img.hidden=false;
    } catch(error) {
      console.warn('PPTX rich rendering unavailable; switching to Canvas',error);
      slideView='canvas';
      if(tick===drawing) await renderPptSlide();
      return;
    }
  } else if(pptRenderer) {
    img.hidden=true;
    canvas.hidden=false;
    await pptRenderer.renderSlide(pptIndex,canvas,1024);
    if(tick!==drawing) return;
    canvas.style.width='100%';
    canvas.style.height='auto';
  } else throw Error(t('표시 가능한 슬라이드 렌더러가 없습니다.'));
  if(tick===drawing){
    $('slide-view-toggle').textContent=slideView==='modern'?t('기존 보기'):t('호환 보기');
    applySlideZoom(slideZoom);
  }
}
async function repaintPpt() {
  $('slide-page').textContent=(pptIndex+1)+' / '+slideCount();
  $('slide-prev').disabled=pptIndex===0;
  $('slide-next').disabled=pptIndex===slideCount()-1;
  slideZoom=1;
  await renderPptSlide();
  updateShapeSelect();
}
export async function openSlides(data, statusCallback) {
  notify=statusCallback;
  pptOriginal=data;
  pptBytes=data;
  pptRenderer=null;modernPpt=null;pptWriter=null;
  if(window.NasPptxModern?.parse) {
    try {
      modernPpt=await window.NasPptxModern.parse(data);
      if(!modernPpt?.slides?.length) modernPpt=null;
    } catch(err) {console.warn('PPTX rich parse fallback',err);}
  }
  try {
    pptRenderer=new PptxRenderer();
    await pptRenderer.load(data);
    pptWriter=PptxWriter.fromRenderer(pptRenderer);
  } catch(err) {
    pptRenderer=null;
    if(!modernPpt) throw err;
  }
  if(!slideCount()) throw Error(t('열 수 있는 PPTX 슬라이드가 없습니다.'));
  slideView=modernPpt?'modern':'canvas';
  pptChanged=false;
  pptIndex=0;
  $('slides-shell').hidden=false;
  $('slide-prev').onclick=async()=>{
    if(pptIndex>0) {pptIndex--;await repaintPpt();}
  };
  $('slide-next').onclick=async()=>{
    if(pptIndex<slideCount()-1) {pptIndex++;await repaintPpt();}
  };
  $('slide-view-toggle').onclick=async()=>{
    if(!modernPpt||!pptRenderer)return;
    slideView=slideView==='modern'?'canvas':'modern';
    await renderPptSlide();
  };
  $('slide-view-toggle').disabled=!modernPpt||!pptRenderer;
  installPinch($('slide-stage'),()=>slideZoom,applySlideZoom,1,4);
  installSlideSwipe($('slide-stage'),direction=>{
    if(slideZoom>1.12)return;
    const next=pptIndex+direction;
    if(next>=0&&next<slideCount()) {pptIndex=next;repaintPpt();}
  });
  $('slide-edit-toggle').onclick=()=>{
    $('slide-editor').hidden=!$('slide-editor').hidden;
  };
  $('slide-close').onclick=()=>{$('slide-editor').hidden=true;};
  $('slide-shape').onchange=()=>{
    $('slide-text').value=pptShapes[Number($('slide-shape').value)]?.text||'';
  };
  $('slide-apply').onclick=async()=>{
    if(editing||!pptWriter)return;
    const shape=pptShapes[Number($('slide-shape').value)];
    if(!shape) return;
    const text=$('slide-text').value;
    if(shape.text===text){$('slide-editor').hidden=true;return;}
    editing=true;
    try {
      pptWriter.setShapeText(pptIndex,shape.name,text,{preserveFormatting:true});
      pptBytes=await pptWriter.save();
      const fresh=new PptxRenderer();
      await fresh.load(pptBytes);
      pptRenderer.destroy();
      pptRenderer=fresh;
      if(window.NasPptxModern?.parse){
        try{modernPpt=await window.NasPptxModern.parse(pptBytes);}
        catch(err){console.warn('rich renderer after edit',err);modernPpt=null;}
      }
      pptChanged=true;
      await repaintPpt();
      update(t('슬라이드 텍스트가 수정됐습니다. 아래 원본 저장을 누르세요.'));
      $('slide-editor').hidden=true;
    } catch(err){update(t('PowerPoint 편집 실패: ')+err.message);}
    finally{editing=false;}
  };
  await repaintPpt();
  update('PowerPoint 준비 완료 · '+slideCount()+'장 · 좌우 스와이프/두 손가락 확대 지원');
}
export async function exportSlides() {
  if(editing) throw Error(t('슬라이드 편집을 마친 뒤 저장하세요.'));
  if(!pptChanged) return pptOriginal;
  return pptWriter.save();
}
