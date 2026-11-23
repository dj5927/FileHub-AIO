// Document-only pinch zoom. Never zooms toolbars or the Android status bar.
export function installPinch(target, getScale, setScale, min=0.6, max=3) {
  let baseline=0, initial=1;
  const distance=t=>Math.hypot(t[0].clientX-t[1].clientX,
                                t[0].clientY-t[1].clientY);
  const clamp=value=>Math.min(max,Math.max(min,value));
  target.addEventListener('touchstart',e=>{
    if(e.touches.length===2){baseline=distance(e.touches);initial=getScale();}
  },{passive:true});
  target.addEventListener('touchmove',e=>{
    if(e.touches.length===2 && baseline>5) {
      const scale=clamp(initial*distance(e.touches)/baseline);
      setScale(scale);
      if(e.cancelable)e.preventDefault();
    }
  },{passive:false});
  target.addEventListener('touchend',e=>{
    if(e.touches.length<2) baseline=0;
  },{passive:true});
  target.addEventListener('touchcancel',()=>baseline=0,{passive:true});
  target.addEventListener('wheel',e=>{
    if(!e.ctrlKey)return;
    if(e.cancelable)e.preventDefault();
    setScale(clamp(getScale()*(e.deltaY<0?1.1:0.9)));
  },{passive:false});
}

/**
 * Keep virtualized spreadsheet cells mounted throughout a pinch. Replacing
 * touch targets during touchmove makes Android WebView cancel the gesture,
 * which previously stopped scaling after only 1-2%.
 */
export function installSpreadsheetPinch(target, grid, min=0.55, max=3) {
  let active=false, awaitingRelease=false;
  let baseline=0, initialZoom=1;
  const distance=touches=>Math.hypot(
    touches[0].clientX-touches[1].clientX,
    touches[0].clientY-touches[1].clientY);
  const midpoint=touches=>{
    const rect=target.getBoundingClientRect();
    return {
      x:(touches[0].clientX+touches[1].clientX)/2-rect.left,
      y:(touches[0].clientY+touches[1].clientY)/2-rect.top
    };
  };
  function begin(e) {
    if(e.touches.length!==2)return;
    const gap=distance(e.touches);
    if(gap<=8)return;
    baseline=gap;
    initialZoom=grid.getZoom();
    awaitingRelease=false;
    active=true;
    grid.beginPinch(midpoint(e.touches));
    if(e.cancelable)e.preventDefault();
  }
  target.addEventListener('touchstart',e=>{
    if(e.touches.length===2)begin(e);
  },{passive:false,capture:true});
  target.addEventListener('touchmove',e=>{
    if(!active && e.touches.length===2)begin(e);
    if(!active)return;
    if(e.cancelable)e.preventDefault();
    if(e.touches.length<2 || baseline<=8 || awaitingRelease)return;
    // No RAF delay: zoom follows every finger movement, and the grid is
    // lightweight while pinching because its cell DOM is not rebuilt.
    grid.updatePinch(Math.min(max,Math.max(min,
      initialZoom*distance(e.touches)/baseline)),midpoint(e.touches));
  },{passive:false,capture:true});
  target.addEventListener('touchend',e=>{
    if(!active)return;
    if(e.cancelable)e.preventDefault();
    if(e.touches.length===0){
      active=false;
      awaitingRelease=false;
      grid.endPinch();
    }else if(e.touches.length===1){
      // Wait until both fingers leave before rebuilding any virtual cells.
      awaitingRelease=true;
    }
  },{passive:false,capture:true});
  target.addEventListener('touchcancel',e=>{
    if(!active)return;
    // WebView may cancel a gesture without delivering the final touchend.
    // Always reset so ordinary one-finger scrolling can resume.
    active=false;
    awaitingRelease=false;
    grid.endPinch();
  },{passive:true,capture:true});
  target.addEventListener('wheel',e=>{
    if(!e.ctrlKey)return;
    if(e.cancelable)e.preventDefault();
    grid.setZoom(Math.min(max,Math.max(min,
      grid.getZoom()*(e.deltaY<0?1.08:1/1.08))),{
        x:e.clientX-target.getBoundingClientRect().left,
        y:e.clientY-target.getBoundingClientRect().top
      });
  },{passive:false});
}

export function installSlideSwipe(target, go) {
  let initialX=0,initialY=0,startTime=0,hadPinch=false;
  target.addEventListener('touchstart',e=>{
    if(e.touches.length>1){hadPinch=true;return;}
    if(e.touches.length===1) {
      hadPinch=false;
      initialX=e.touches[0].clientX;
      initialY=e.touches[0].clientY;
      startTime=Date.now();
    }
  },{passive:true});
  target.addEventListener('touchmove',e=>{
    if(e.touches.length>1) hadPinch=true;
  },{passive:true});
  target.addEventListener('touchend',e=>{
    if(hadPinch || e.touches.length || !e.changedTouches.length) return;
    const dx=e.changedTouches[0].clientX-initialX;
    const dy=e.changedTouches[0].clientY-initialY;
    if(Math.abs(dx)>65 && Math.abs(dx)>Math.abs(dy)*1.4 &&
       Date.now()-startTime<1100) go(dx<0?1:-1);
  },{passive:true});
}
