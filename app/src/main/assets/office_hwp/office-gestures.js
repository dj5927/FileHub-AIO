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
