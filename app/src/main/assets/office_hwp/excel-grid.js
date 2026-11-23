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
  function buildPrefix(sheet,meta){
    let range;
    try{range=XLSX.utils.decode_range(sheet['!ref']||'A1');}
    catch{range={s:{r:0,c:0},e:{r:0,c:0}};}
    // Expose all used rows and columns in one uninterrupted scroll surface.
    state.maxRow=Math.min(LIMIT_ROW,Math.max(70,range.e.r+41));
    state.maxCol=Math.min(LIMIT_COL,Math.max(14,range.e.c+7));
    state.rowPrefix=new Float64Array(state.maxRow+1);
    state.colPrefix=new Float64Array(state.maxCol+1);
    for(let r=0;r<state.maxRow;r++){
      const size=meta?.rowHeights.get(r)||
        sheet['!rows']?.[r]?.hpx||sheet['!rows']?.[r]?.hpt*96/72||27;
      state.rowPrefix[r+1]=state.rowPrefix[r]+Math.min(400,Math.max(18,size));
    }
    for(let c=0;c<state.maxCol;c++){
      const size=meta?.colWidths.get(c)||
        sheet['!cols']?.[c]?.wpx||Math.round((sheet['!cols']?.[c]?.wch||12)*7+5);
      state.colPrefix[c+1]=state.colPrefix[c]+Math.min(600,Math.max(32,size));
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
        const styleId=state.meta?.cells.get(address) ?? cell?.s ?? 0;
        const style=styleContext?.styles?.[styleId];
        if(style)Object.assign(node.style,style);
        if(cell?.t==='n' && !style?.textAlign)node.style.textAlign='right';
        if(node.style.textAlign==='right')node.style.justifyContent='flex-end';
        else if(node.style.textAlign==='center')
          node.style.justifyContent='center';
        if(style?.verticalAlign==='top')node.style.alignItems='flex-start';
        else if(style?.verticalAlign==='bottom')
          node.style.alignItems='flex-end';
        node.addEventListener('click',()=>onSelect(address));
        node.addEventListener('dblclick',()=>{
          onSelect(address);
          if(!readOnly)document.getElementById('formula-input').focus();
        });
        append(node);
      }
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
