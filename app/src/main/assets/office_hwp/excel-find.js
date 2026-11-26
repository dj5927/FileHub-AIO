// Sparse-sheet search. Only the active worksheet is passed to this function.
function folded(value){return String(value??'').normalize('NFKC').toLocaleLowerCase();}
export function sheetCellPreview(cell){
  return String(cell?.w ?? cell?.v ?? (cell?.f?'='+cell.f:''));
}
export function findSheetCells(sheet,query){
  const needle=folded(query).trim();
  if(!needle||!sheet)return [];
  const hits=[];
  for(const address of Object.keys(sheet)){
    const m=/^([A-Z]{1,3})([1-9][0-9]{0,6})$/.exec(address);
    if(!m)continue;
    const cell=sheet[address];
    if(!cell)continue;
    if(![cell.w,cell.v,cell.f===undefined?undefined:'='+cell.f]
      .some(value=>value!==undefined&&value!==null&&folded(value).includes(needle)))continue;
    let col=0;
    for(const letter of m[1])col=col*26+letter.charCodeAt(0)-64;
    const row=Number(m[2]);
    if(row>1048576||col>16384)continue;
    hits.push({address,row,col});
  }
  hits.sort((a,b)=>a.row-b.row||a.col-b.col);
  return hits.map(hit=>hit.address);
}
