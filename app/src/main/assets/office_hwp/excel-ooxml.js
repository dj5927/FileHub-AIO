/**
 * OOXML spreadsheet presentation and lossless cell-edit export.
 *
 * SheetJS community edition can read cell values but does not faithfully
 * round-trip all Office styling. Render directly from xl/styles.xml and
 * worksheet XML; save only changed cell XML into the ORIGINAL archive,
 * preserving untouched formatting, drawings, images, filters, and sheet tabs.
 */
const NS='http://schemas.openxmlformats.org/spreadsheetml/2006/main';
const REL='http://schemas.openxmlformats.org/officeDocument/2006/relationships';
const fallbackTheme=['#FFFFFF','#000000','#EEECE1','#1F497D',
  '#4F81BD','#C0504D','#9BBB59','#8064A2','#4BACC6','#F79646'];
const indexed=['#000000','#FFFFFF','#FF0000','#00FF00','#0000FF',
  '#FFFF00','#FF00FF','#00FFFF','#000000','#FFFFFF','#FF0000',
  '#00FF00','#0000FF','#FFFF00','#FF00FF','#00FFFF'];

const parse=xml=>new DOMParser().parseFromString(xml,'application/xml');
const tags=(element,name)=>Array.from(element?.getElementsByTagNameNS('*',name)||[]);
const first=(element,name)=>tags(element,name)[0]||null;
const attr=(node,name)=>node?.getAttribute(name)||'';
const text=node=>node?.textContent||'';
const rawColor=hex=>{
  if(!hex || !/^[a-fA-F0-9]{6,8}$/.test(hex))return null;
  return '#'+hex.slice(-6).toUpperCase();
};
function color(node,theme) {
  if(!node) return null;
  if(node.hasAttribute('rgb')) return rawColor(attr(node,'rgb'));
  if(node.hasAttribute('indexed'))
    return indexed[Number(attr(node,'indexed'))]||null;
  if(node.hasAttribute('theme')){
    let value=theme[Number(attr(node,'theme'))]||null;
    if(!value)return null;
    const tint=Number(attr(node,'tint')||0);
    if(tint){
      const parts=[1,3,5].map(i=>parseInt(value.slice(i,i+2),16));
      value='#'+parts.map(c=>Math.max(0,Math.min(255,Math.round(
        tint<0 ? c*(1+tint) : c*(1-tint)+255*tint))).toString(16).padStart(2,'0')).join('');
    }
    return value;
  }
  return null;
}
function workbookTheme(xml) {
  if(!xml)return fallbackTheme;
  const scheme=first(parse(xml),'clrScheme');
  if(!scheme)return fallbackTheme;
  const names=['lt1','dk1','lt2','dk2','accent1','accent2',
    'accent3','accent4','accent5','accent6'];
  return names.map((name,i)=>{
    const parent=first(scheme,name);
    const item=first(parent,'srgbClr')||first(parent,'sysClr');
    return rawColor(attr(item,'val')||attr(item,'lastClr'))||fallbackTheme[i];
  });
}
function styleCatalog(xml,theme) {
  if(!xml)return [{}];
  const doc=parse(xml);
  const fonts=tags(first(doc,'fonts'),'font');
  const fills=tags(first(doc,'fills'),'fill');
  const borders=tags(first(doc,'borders'),'border');
  const xfs=tags(first(doc,'cellXfs'),'xf');
  return xfs.map(xf=>{
    const style={};
    const font=fonts[Number(attr(xf,'fontId'))];
    if(font){
      if(first(font,'b'))style.fontWeight='700';
      if(first(font,'i'))style.fontStyle='italic';
      if(first(font,'u'))style.textDecoration='underline';
      const size=Number(attr(first(font,'sz'),'val'));
      if(size>0 && size<80)style.fontSize=(size*96/72)+'px';
      const fontName=attr(first(font,'name'),'val');
      if(fontName && /^[^"'<>]{1,80}$/.test(fontName))
        style.fontFamily='"'+fontName+'","Noto Sans KR",sans-serif';
      const fg=color(first(font,'color'),theme);if(fg)style.color=fg;
    }
    const fill=fills[Number(attr(xf,'fillId'))];
    const pattern=first(fill,'patternFill');
    if(pattern && attr(pattern,'patternType')==='solid'){
      const bg=color(first(pattern,'fgColor'),theme);
      if(bg)style.backgroundColor=bg;
    }
    const border=borders[Number(attr(xf,'borderId'))];
    if(border){
      for(const edge of ['top','right','bottom','left']){
        const side=first(border,edge);
        if(!side || !attr(side,'style'))continue;
        const width=['medium','thick','double'].includes(attr(side,'style'))?'2px':'1px';
        style['border'+edge[0].toUpperCase()+edge.slice(1)]=
          width+' solid '+(color(first(side,'color'),theme)||'#777');
      }
    }
    const alignment=first(xf,'alignment');
    const align=attr(alignment,'horizontal');
    if(['left','right','center','justify'].includes(align))style.textAlign=align;
    const valign=attr(alignment,'vertical');
    if(['top','bottom','center'].includes(valign))
      style.verticalAlign=valign==='center'?'middle':valign;
    if(attr(alignment,'wrapText')==='1'){
      style.whiteSpace='pre-wrap';style.wordBreak='break-word';
    }
    return style;
  });
}
function resolvePath(target) {
  const url=new URL(target,'https://office.local/xl/workbook.xml');
  const path=url.pathname.substring(1);
  if(!path.startsWith('xl/') || path.includes('..'))
    throw Error('Worksheet relation points outside workbook');
  return path;
}
function rowColMeta(xml) {
  const doc=parse(xml);
  const cells=new Map(),rowHeights=new Map(),colWidths=new Map();
  for(const row of tags(first(doc,'sheetData'),'row')){
    const n=Number(attr(row,'r'))-1;
    const h=Number(attr(row,'ht'));
    if(Number.isInteger(n) && n>=0 && h>0)
      rowHeights.set(n,Math.max(12,Math.min(400,h*96/72)));
  }
  for(const column of tags(first(doc,'cols'),'col')){
    const min=Number(attr(column,'min'))-1,max=Number(attr(column,'max'))-1;
    const w=Number(attr(column,'width'));
    if(Number.isFinite(w) && min>=0 && max>=min)
      for(let c=min;c<=Math.min(max,16383);c++)
        colWidths.set(c,Math.max(32,Math.min(600,Math.round(w*7+5))));
  }
  for(const cell of tags(first(doc,'sheetData'),'c'))
    if(attr(cell,'r'))cells.set(attr(cell,'r'),Number(attr(cell,'s')||0));
  const merges=tags(first(doc,'mergeCells'),'mergeCell')
    .map(m=>attr(m,'ref')).filter(Boolean);
  return {cells,rowHeights,colWidths,merges};
}

export async function openXlsxArchive(bytes, sheetNames) {
  if(!window.JSZip || bytes[0]!==0x50 || bytes[1]!==0x4b)return null;
  const zip=await JSZip.loadAsync(bytes);
  const workbookFile=zip.file('xl/workbook.xml');
  if(!workbookFile)return null;
  const doc=parse(await workbookFile.async('string'));
  const relFile=zip.file('xl/_rels/workbook.xml.rels');
  if(!relFile)return null;
  const rels=new Map(tags(parse(await relFile.async('string')),'Relationship')
    .map(r=>[attr(r,'Id'),attr(r,'Target')]));
  const entries=tags(first(doc,'sheets'),'sheet');
  const paths=sheetNames.map((name,i)=>{
    const sheet=entries.find(s=>attr(s,'name')===name)||entries[i];
    const id=sheet?.getAttributeNS(REL,'id')||attr(sheet,'r:id');
    return id&&rels.has(id)?resolvePath(rels.get(id)):null;
  });
  const themeFile=zip.file('xl/theme/theme1.xml');
  const theme=workbookTheme(themeFile?await themeFile.async('string'):null);
  const stylesFile=zip.file('xl/styles.xml');
  const styles=styleCatalog(stylesFile?await stylesFile.async('string'):null,theme);
  const sheetMeta=[];
  for(const path of paths){
    const file=path&&zip.file(path);
    sheetMeta.push(file?rowColMeta(await file.async('string')):
      {cells:new Map(),rowHeights:new Map(),colWidths:new Map(),merges:[]});
  }
  return {zip,paths,styles,sheetMeta,bytes:new Uint8Array(bytes)};
}

function ensureRow(sheetData,number) {
  for(const row of tags(sheetData,'row')){
    const current=Number(attr(row,'r'));
    if(current===number)return row;
    if(current>number){
      const added=sheetData.ownerDocument.createElementNS(NS,'row');
      added.setAttribute('r',String(number));
      sheetData.insertBefore(added,row);
      return added;
    }
  }
  const added=sheetData.ownerDocument.createElementNS(NS,'row');
  added.setAttribute('r',String(number));sheetData.appendChild(added);
  return added;
}
function setCell(row,address,change) {
  const doc=row.ownerDocument;
  const rowCells=tags(row,'c');
  let cell=rowCells.find(c=>attr(c,'r')===address);
  if(!cell){
    cell=doc.createElementNS(NS,'c');cell.setAttribute('r',address);
    const currentCol=XLSX.utils.decode_cell(address).c;
    const next=rowCells.find(c=>XLSX.utils.decode_cell(attr(c,'r')).c>currentCol);
    if(next)row.insertBefore(cell,next);else row.appendChild(cell);
  }
  for(const child of Array.from(cell.children))
    if(['v','is','f'].includes(child.localName))cell.removeChild(child);
  if(change.kind==='blank'){
    cell.removeAttribute('t');
  } else if(change.kind==='formula'){
    cell.removeAttribute('t');
    const formula=doc.createElementNS(NS,'f');
    formula.textContent=change.value.substring(1);
    cell.appendChild(formula);
  } else if(change.kind==='number'){
    cell.setAttribute('t','n');
    const value=doc.createElementNS(NS,'v');value.textContent=String(Number(change.value));
    cell.appendChild(value);
  } else {
    cell.setAttribute('t','inlineStr');
    const inline=doc.createElementNS(NS,'is');
    const t=doc.createElementNS(NS,'t');
    if(/^\s|\s$/.test(change.value))t.setAttribute('xml:space','preserve');
    t.textContent=change.value;inline.appendChild(t);cell.appendChild(inline);
  }
}

export async function exportXlsxArchive(context,changes) {
  if(!context)return null;
  if(!changes.size)return context.bytes;
  const zip=await JSZip.loadAsync(context.bytes);
  const updated=new Map();
  for(const [key,change] of changes){
    const [indexString,address]=key.split(':');
    const index=Number(indexString),path=context.paths[index];
    if(!path)throw Error('Sheet XML path not available');
    if(!updated.has(path)){
      const entry=zip.file(path);
      if(!entry)throw Error('Sheet XML missing: '+path);
      updated.set(path,parse(await entry.async('string')));
    }
    const doc=updated.get(path),data=first(doc,'sheetData');
    const row=XLSX.utils.decode_cell(address).r+1;
    setCell(ensureRow(data,row),address,change);
  }
  for(const [path,doc] of updated)
    zip.file(path,new XMLSerializer().serializeToString(doc));
  const wbFile=zip.file('xl/workbook.xml');
  if(wbFile){
    const wbDoc=parse(await wbFile.async('string'));
    let props=first(wbDoc,'calcPr');
    if(!props){props=wbDoc.createElementNS(NS,'calcPr');wbDoc.documentElement.appendChild(props);}
    props.setAttribute('fullCalcOnLoad','1');
    zip.file('xl/workbook.xml',new XMLSerializer().serializeToString(wbDoc));
  }
  return zip.generateAsync({type:'uint8array',compression:'DEFLATE',
    compressionOptions:{level:4}});
}
