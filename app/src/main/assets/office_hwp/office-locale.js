const language=new URLSearchParams(location.search).get('lang')||'ko';
const column={en:1,ja:2,zh:3}[language]||0;
const dictionary=new Map();
export function t(text) {
  if(!column || typeof text!=='string')return text;
  return dictionary.get(text)||text;
}
// Translate only the packaged UI before any user document is loaded.
export async function localizeOfficeShell(){
  document.documentElement.lang=column?language:'ko';
  if(!column)return;
  try{
    const response=await fetch('/locale/ui.tsv',{cache:'no-store'});
    if(!response.ok)throw Error('Locale unavailable');
    for(const line of (await response.text()).split(/\r?\n/)){
      if(!line||line.startsWith('#'))continue;
      const fields=line.split('\t').map(x=>x.replaceAll('\\n','\n'));
      if(fields.length===4)dictionary.set(fields[0],fields[column]);
    }
    const walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);
    for(let node=walker.nextNode();node;node=walker.nextNode()){
      if(node.parentElement?.closest('script,style'))continue;
      const value=node.nodeValue.trim(),translated=t(value);
      if(value!==translated)node.nodeValue=node.nodeValue.replace(value,translated);
    }
    for(const el of document.querySelectorAll('[aria-label],[placeholder],[alt],[title]')){
      for(const attr of ['aria-label','placeholder','alt','title']){
        if(el.hasAttribute(attr))el.setAttribute(attr,t(el.getAttribute(attr)));
      }
    }
    document.title=t(document.title);
  }catch(e){console.warn('Office UI locale unavailable');}
}
