import { t } from '/office-locale.js';
/**
 * Continuous two-dimensional, single-finger spreadsheet scrolling.
 * Pixel-coordinate virtualization means even 100k rows use a few hundred DOM
 * nodes. Two-finger pinch is installed by the existing gesture helper.
 */
const LIMIT_ROW=1048576,LIMIT_COL=16384;
const HEADER_WIDTH=42,HEADER_HEIGHT=29;
function lowerBound(prefix,offset){
  let low=0,high=prefix.length-2;
  while(low<high){
    const mid=(low+high+1)>>>1;
    if(prefix[mid]<=offset)low=mid;else high=mid-1;
  }
  return low;
}
function element(cls,text,x,y,w,h){
  const node=document.createElement('div');
  node.className=cls;
  if(text!==undefined)node.textContent=String(text);
  node.style.cssText='left:'+x+'px;top:'+y+'px;width:'+w+
    'px;height:'+h+'px;';
  return node;
}

export function mountExcelGrid(scroll,workbook,styleContext,getSheetIndex,
                               selectedAddress,onSelect,readOnly=false){
  const surface=document.createElement('div');
  surface.id='sheet-surface';
  scroll.replaceChildren(surface);
  const state={rowPrefix:[],colPrefix:[],maxRow:0,maxCol:0,
    sheet:null,meta:null,zoom:1,selected:selectedAddress,
    raf:false,lastKey:'',visible:[],pinching:false,pinchAnchor:null};
  const measure=document.createElement('canvas').getContext('2d');
  function styleFor(address,row,col,cell){
    const id=state.meta?.cells.has(address)?
      state.meta.cells.get(address):
      (cell?.s ?? state.meta?.rowStyles?.get(row) ??
        state.meta?.colStyles?.get(col) ?? 0);
    return styleContext?.styles?.[id] || null;
  }
  function buildPrefix(sheet,meta){
    let range;
    try{range=XLSX.utils.decode_range(sheet['!ref']||'A1');}
    catch{range={s:{r:0,c:0},e:{r:0,c:0}};}
    // Expose all used rows and columns in one uninterrupted scroll surface.
    const pictures=meta?.images||[];
    const pictureMaxRow=pictures.reduce((m,p)=>Math.max(m,
      p.to?.row??((p.from?.row||0)+100)),0);
    const pictureMaxCol=pictures.reduce((m,p)=>Math.max(m,
      p.to?.col??((p.from?.col||0)+30)),0);
    state.maxRow=Math.min(LIMIT_ROW,Math.max(70,range.e.r+41,pictureMaxRow+5));
    state.maxCol=Math.min(LIMIT_COL,Math.max(14,range.e.c+7,pictureMaxCol+5));
    state.rowPrefix=new Float64Array(state.maxRow+1);
    state.colPrefix=new Float64Array(state.maxCol+1);
    for(let r=0;r<state.maxRow;r++){
      const size=meta?.rowHeights.get(r)||
        sheet['!rows']?.[r]?.hpx||sheet['!rows']?.[r]?.hpt*96/72||
        meta?.defaultRowPx||20;
      state.rowPrefix[r+1]=state.rowPrefix[r]+Math.min(400,Math.max(16,size));
    }
    for(let c=0;c<state.maxCol;c++){
      const size=meta?.colWidths.get(c)||
        sheet['!cols']?.[c]?.wpx||
        (sheet['!cols']?.[c]?.wch ?
          Math.round(sheet['!cols'][c].wch*7+5) : null)||
        meta?.defaultColPx||64;
      state.colPrefix[c+1]=state.colPrefix[c]+Math.min(600,Math.max(32,size));
    }
    // Excel auto-sizes rows with wrapped content unless the row height was
    // explicitly set. A fixed-height row must keep its intentional clipping.
    const autoHeights=new Map();
    for(const [address,cell] of Object.entries(sheet)){
      if(!/^[A-Z]+[1-9][0-9]*$/.test(address) ||
         typeof cell?.v!=='string' || !cell.v.length)continue;
      const pos=XLSX.utils.decode_cell(address);
      if(pos.r>=state.maxRow || pos.c>=state.maxCol ||
         meta?.rowHeights?.has(pos.r))continue;
      const css=styleFor(address,pos.r,pos.c,cell);
      if(css?.whiteSpace!=='pre-wrap')continue;
      const merged=(sheet['!merges']||[]).find(m=>
        m.s.r===pos.r && m.s.c===pos.c);
      const endCol=merged?Math.min(state.maxCol,merged.e.c+1):pos.c+1;
      const width=state.colPrefix[endCol]-state.colPrefix[pos.c]-8;
      if(width<=0)continue;
      const fontSize=parseFloat(css.fontSize)||12;
      let lines=0;
      for(const part of cell.v.split('\n')){
        let units=0;
        for(const char of part)units+=char.charCodeAt(0)>255?1:0.55;
        lines+=Math.max(1,Math.ceil(units*fontSize/width));
      }
      const height=Math.min(400,Math.max(meta?.defaultRowPx||20,
        Math.ceil(lines*fontSize*1.3+6)));
      if(height>(autoHeights.get(pos.r)||0))autoHeights.set(pos.r,height);
    }
    if(autoHeights.size){
      for(let r=0;r<state.maxRow;r++){
        const baseline=state.rowPrefix[r+1]-state.rowPrefix[r];
        state.rowPrefix[r+1]=state.rowPrefix[r]+
          Math.max(baseline,autoHeights.get(r)||0);
      }
    }
  }
  function updateSurfaceSize(){
    surface.style.zoom=String(state.zoom);
    surface.style.width=(HEADER_WIDTH+state.colPrefix[state.maxCol])+'px';
    surface.style.height=(HEADER_HEIGHT+state.rowPrefix[state.maxRow])+'px';
  }
  function mergeFor(r,c){
    const merges=state.sheet['!merges']||[];
    for(const merged of merges){
      if(r>=merged.s.r && r<=merged.e.r &&
         c>=merged.s.c && c<=merged.e.c)return merged;
    }
    return null;
  }
  function cellBlocksText(cell) {
    return !!(cell && (cell.f ||
      (cell.v!==undefined && cell.v!==null && cell.v!=='')));
  }
  function overflowWidth(r,c,style,merged) {
    if(merged || style?.whiteSpace==='pre-wrap' || style?.shrinkToFit)
      return null;
    const rightward=style?.textAlign!=='right';
    let left=HEADER_WIDTH+state.colPrefix[c];
    let right=HEADER_WIDTH+state.colPrefix[c+1];
    for(let step=1;step<=48;step++) {
      const candidate=c+(rightward?step:-step);
      if(candidate<0 || candidate>=state.maxCol)break;
      const address=XLSX.utils.encode_cell({r,c:candidate});
      if(cellBlocksText(state.sheet[address]) || mergeFor(r,candidate))break;
      if(rightward)right=HEADER_WIDTH+state.colPrefix[candidate+1];
      else left=HEADER_WIDTH+state.colPrefix[candidate];
      if(right-left>=3600)break;
    }
    return {
      start:left-(HEADER_WIDTH+state.colPrefix[c]),
      width:Math.max(0,right-left)
    };
  }
  function imageBox(pic) {
    const position=point=>({
      x:HEADER_WIDTH+state.colPrefix[Math.min(point.col,state.maxCol)]+point.colOff,
      y:HEADER_HEIGHT+state.rowPrefix[Math.min(point.row,state.maxRow)]+point.rowOff
    });
    const start=pic.absolute?
      {x:HEADER_WIDTH+pic.absolute.x,y:HEADER_HEIGHT+pic.absolute.y}:
      position(pic.from);
    const end=pic.to?position(pic.to):{
      x:start.x+pic.cx,y:start.y+pic.cy
    };
    return {x:start.x,y:start.y,w:end.x-start.x,h:end.y-start.y};
  }
  function visibleRect(){
    const zoom=state.zoom, top=scroll.scrollTop/zoom,
      left=scroll.scrollLeft/zoom;
    const r0=Math.max(0,lowerBound(state.rowPrefix,
      Math.max(0,top-HEADER_HEIGHT))-5);
    const c0=Math.max(0,lowerBound(state.colPrefix,
      Math.max(0,left-HEADER_WIDTH))-2);
    const r1=Math.min(state.maxRow,r0+Math.max(30,
      Math.ceil(scroll.clientHeight/18/zoom)+24));
    const c1=Math.min(state.maxCol,c0+Math.max(12,
      Math.ceil(scroll.clientWidth/32/zoom)+7));
    return {r0,r1,c0,c1,top,left};
  }
  function pinHeaders(rect){
    for(const n of state.visible){
      if(n.classList.contains('excel-col-header'))n.style.top=rect.top+'px';
      if(n.classList.contains('excel-row-header'))n.style.left=rect.left+'px';
      if(n.classList.contains('excel-corner')){
        n.style.top=rect.top+'px';n.style.left=rect.left+'px';
      }
    }
  }
  function render(force=false){
    state.raf=false;
    if(state.pinching)return;
    const rect=visibleRect();
    const key=[getSheetIndex(),rect.r0,rect.r1,rect.c0,rect.c1].join(':');
    if(!force && key===state.lastKey){pinHeaders(rect);return;}
    state.lastKey=key;
    const fragment=document.createDocumentFragment(),visible=[];
    const append=n=>{fragment.appendChild(n);visible.push(n);};
    for(let c=rect.c0;c<rect.c1;c++){
      const node=element('excel-col-header',XLSX.utils.encode_col(c),
        HEADER_WIDTH+state.colPrefix[c],rect.top,
        state.colPrefix[c+1]-state.colPrefix[c],HEADER_HEIGHT);
      append(node);
    }
    for(let r=rect.r0;r<rect.r1;r++){
      const top=HEADER_HEIGHT+state.rowPrefix[r];
      const height=state.rowPrefix[r+1]-state.rowPrefix[r];
      append(element('excel-row-header',r+1,rect.left,top,HEADER_WIDTH,height));
      for(let c=rect.c0;c<rect.c1;c++){
        const merged=mergeFor(r,c);
        if(merged && (r!==merged.s.r || c!==merged.s.c))continue;
        const address=XLSX.utils.encode_cell({r,c});
        const cell=state.sheet[address];
        const text=cell ? (cell.w ?? cell.v ?? (cell.f?'='+cell.f:'')) : '';
        const endCol=merged?Math.min(merged.e.c+1,state.maxCol):c+1;
        const endRow=merged?Math.min(merged.e.r+1,state.maxRow):r+1;
        const node=element('excel-cell'+(address===state.selected?' selected':''),
          text,HEADER_WIDTH+state.colPrefix[c],top,
          state.colPrefix[endCol]-state.colPrefix[c],
          state.rowPrefix[endRow]-state.rowPrefix[r]);
        node.dataset.address=address;
        const style=styleFor(address,r,c,cell);
        if(style){
          const {shrinkToFit,...css}=style;
          Object.assign(node.style,css);
        }
        if(merged){
          // Merge edge strokes may be attached to hidden bottom/right cells.
          const rightCell=XLSX.utils.encode_cell({r,c:merged.e.c});
          const bottomCell=XLSX.utils.encode_cell({r:merged.e.r,c});
          const cornerCell=XLSX.utils.encode_cell({r:merged.e.r,c:merged.e.c});
          const right=styleFor(rightCell,r,merged.e.c,state.sheet[rightCell]);
          const bottom=styleFor(bottomCell,merged.e.r,c,state.sheet[bottomCell]);
          const corner=styleFor(cornerCell,merged.e.r,merged.e.c,state.sheet[cornerCell]);
          if(!style?.borderRight && (right?.borderRight||corner?.borderRight))
            node.style.borderRight=right?.borderRight||corner.borderRight;
          if(!style?.borderBottom && (bottom?.borderBottom||corner?.borderBottom))
            node.style.borderBottom=bottom?.borderBottom||corner.borderBottom;
        }
        if(cell?.t==='n' && !style?.textAlign)node.style.textAlign='right';
        if(node.style.textAlign==='right')node.style.justifyContent='flex-end';
        else if(node.style.textAlign==='center')
          node.style.justifyContent='center';
        if(style?.verticalAlign==='top')node.style.alignItems='flex-start';
        else if(style?.verticalAlign==='bottom')
          node.style.alignItems='flex-end';
        if(style?.whiteSpace==='pre-wrap')node.style.display='block';
        if(style?.shrinkToFit && text!=='' && measure){
          const fontPx=parseFloat(style.fontSize)||12;
          measure.font=(style.fontStyle||'normal')+' '+
            (style.fontWeight||'400')+' '+fontPx+'px '+
            (style.fontFamily||'Arial,sans-serif');
          const available=Math.max(8,
            state.colPrefix[endCol]-state.colPrefix[c]-12);
          const length=measure.measureText(String(text)).width;
          if(length>available)
            node.style.fontSize=Math.max(5,
              Math.min(fontPx,fontPx*available/length))+'px';
        }
        const isString=cell && typeof cell.v==='string' && cell.v.length>0;
        if(isString && !merged && style?.whiteSpace!=='pre-wrap' &&
           !style?.shrinkToFit) {
          const overflow=overflowWidth(r,c,style,merged);
          const ownWidth=state.colPrefix[c+1]-state.colPrefix[c];
          if(overflow && overflow.width>ownWidth+1) {
            // Only the glyphs extend into adjacent empty cells; preserve
            // each neighbor's original fill, borders and row height.
            node.textContent='';
            const span=document.createElement('span');
            span.className='excel-spill';
            span.textContent=String(text);
            span.style.left=overflow.start+'px';
            span.style.width=overflow.width+'px';
            span.style.justifyContent=style?.textAlign==='right'?'flex-end':'flex-start';
            node.classList.add('excel-overflow');
            node.appendChild(span);
          }
        }
        node.addEventListener('click',()=>onSelect(address));
        node.addEventListener('dblclick',()=>{
          onSelect(address);
          if(!readOnly)document.getElementById('formula-input').focus();
        });
        append(node);
      }
    }
    const viewport={
      left:rect.left-250,top:rect.top-250,
      right:rect.left+scroll.clientWidth/state.zoom+250,
      bottom:rect.top+scroll.clientHeight/state.zoom+250
    };
    for(const pic of state.meta?.images||[]) {
      const box=imageBox(pic);
      if(!(box.w>0 && box.h>0) || box.w>12000 || box.h>12000 ||
         box.x>viewport.right || box.y>viewport.bottom ||
         box.x+box.w<viewport.left || box.y+box.h<viewport.top)continue;
      const img=document.createElement('img');
      img.className='excel-drawing-image';
      img.alt=t('엑셀 삽입 이미지');
      img.draggable=false;
      img.src=pic.src;
      img.style.cssText='left:'+box.x+'px;top:'+box.y+'px;width:'+
        box.w+'px;height:'+box.h+'px;';
      append(img);
    }
    append(element('excel-corner','',rect.left,rect.top,HEADER_WIDTH,HEADER_HEIGHT));
    surface.replaceChildren(fragment);
    state.visible=visible;
  }
  function queueRender(force=false){
    if(state.pinching)return;
    if(force){state.lastKey='';render(true);return;}
    if(!state.raf){
      state.raf=true;
      requestAnimationFrame(()=>render());
    }
  }
  const onScroll=()=>{
    if(state.pinching)pinHeaders(visibleRect());
    else queueRender();
  };
  scroll.addEventListener('scroll',onScroll,{passive:true});
  function load(index,resetScroll=true){
    state.sheet=workbook.Sheets[workbook.SheetNames[index]];
    state.meta=styleContext?.sheetMeta[index]||null;
    buildPrefix(state.sheet,state.meta);
    state.lastKey='';updateSurfaceSize();
    if(resetScroll){scroll.scrollTop=0;scroll.scrollLeft=0;}
    render(true);
  }
  function setZoom(zoom,point){
    const target=Math.max(.55,Math.min(3,zoom));
    const focus=point||{x:scroll.clientWidth/2,y:scroll.clientHeight/2};
    const contentX=(scroll.scrollLeft+focus.x)/state.zoom;
    const contentY=(scroll.scrollTop+focus.y)/state.zoom;
    state.zoom=target;
    surface.style.zoom=String(target);
    scroll.scrollLeft=Math.max(0,contentX*target-focus.x);
    scroll.scrollTop=Math.max(0,contentY*target-focus.y);
    queueRender(true);
  }
  function beginPinch(focus){
    state.pinching=true;
    state.pinchAnchor={
      x:(scroll.scrollLeft+focus.x)/state.zoom,
      y:(scroll.scrollTop+focus.y)/state.zoom
    };
  }
  function updatePinch(zoom,focus){
    if(!state.pinching)return;
    state.zoom=Math.max(.55,Math.min(3,zoom));
    surface.style.zoom=String(state.zoom);
    // The same content point follows the midpoint of the two fingers.
    scroll.scrollLeft=Math.max(0,state.pinchAnchor.x*state.zoom-focus.x);
    scroll.scrollTop=Math.max(0,state.pinchAnchor.y*state.zoom-focus.y);
    pinHeaders(visibleRect());
  }
  function endPinch(){
    if(!state.pinching)return;
    state.pinching=false;
    state.pinchAnchor=null;
    queueRender(true);
  }
  function jump(address){
    let pos;
    try{pos=XLSX.utils.decode_cell(address);}catch{return;}
    if(pos.r>=state.maxRow||pos.c>=state.maxCol)return;
    scroll.scrollLeft=(HEADER_WIDTH+state.colPrefix[pos.c])*state.zoom;
    scroll.scrollTop=(HEADER_HEIGHT+state.rowPrefix[pos.r])*state.zoom;
    queueRender(true);
  }
  function select(address){
    state.selected=address;
    for(const node of state.visible)
      if(node.classList.contains('excel-cell'))
        node.classList.toggle('selected',node.dataset.address===address);
  }
  load(getSheetIndex());
  return {load,select,jump,refresh:()=>queueRender(true),
    getZoom:()=>state.zoom,setZoom,beginPinch,updatePinch,endPinch};
}
