import 'dart:typed_data';
import 'package:material_ui/material_ui.dart';
import 'file_operations.dart';

abstract interface class ExplorerHost {
  Future<Map<Object?,Object?>> explorerPage(String revision,int offset);
  Future<Map<Object?,Object?>> explorerPreview(String id);
}

class ExplorerPage {
  ExplorerPage(Map<Object?,Object?> d):
      revision=d['revision'] as String,offset=d['offset'] as int,
      next=d['next'] as int,total=d['total'] as int,
      rows=(d['rows'] as List).map((e)=>Map<Object?,Object?>.from(e as Map)).toList(){
    if(offset<0||next<offset||next>total||rows.length!=next-offset||
        rows.length>60||(next==offset&&next<total)){
      throw const FormatException('Invalid directory page');
    }
  }
  final String revision;
  final int offset,next,total;
  final List<Map<Object?,Object?>> rows;
}

class ExplorerScreen extends StatefulWidget {
  const ExplorerScreen({super.key,required this.data,required this.labels,
    required this.busy,required this.host,required this.action});
  final Map<Object?,Object?> data;
  final Map<String,String> labels;
  final bool busy;
  final ExplorerHost host;
  final Future<void> Function(String,[String?]) action;
  @override
  State<ExplorerScreen> createState()=>_ExplorerScreenState();
}
class _ExplorerScreenState extends State<ExplorerScreen> {
  final scroll=ScrollController(),crumbScroll=ScrollController();
  final previews=<String,Map<Object?,Object?>>{};
  late ExplorerPage first;
  late List<Map<Object?,Object?>> rows;
  late int next,total;
  bool loading=false,pageError=false;
  String t(String key)=>widget.labels[key]??key;
  bool get more=>next<total;
  @override
  void initState(){
    super.initState();reset();
    scroll.addListener(maybeLoad);
    WidgetsBinding.instance.addPostFrameCallback((_){
      if(mounted&&crumbScroll.hasClients)crumbScroll.jumpTo(crumbScroll.position.maxScrollExtent);
    });
  }
  void reset(){
    first=ExplorerPage(widget.data);rows=List.of(first.rows);
    next=first.next;total=first.total;pageError=false;previews.clear();
  }
  @override
  void didUpdateWidget(covariant ExplorerScreen oldWidget){
    super.didUpdateWidget(oldWidget);
    final updated=ExplorerPage(widget.data);
    if(updated.revision!=first.revision){reset();return;}
    first=updated;
    if(rows.length>=updated.rows.length)rows.replaceRange(0,updated.rows.length,updated.rows);
  }
  void maybeLoad(){
    if(scroll.hasClients&&scroll.position.extentAfter<280&&more&&!loading&&!pageError)loadMore();
  }
  Future<void> loadMore()async{
    if(loading||!more)return;
    final revision=first.revision,offset=next;
    setState((){loading=true;pageError=false;});
    try{
      final page=ExplorerPage(await widget.host.explorerPage(revision,offset));
      if(!mounted||first.revision!=revision)return;
      if(page.revision!=revision||page.offset!=offset||page.total!=total){
        throw const FormatException('Stale directory page');
      }
      setState((){rows.addAll(page.rows);next=page.next;});
    }catch(_){
      if(mounted&&first.revision==revision)setState(()=>pageError=true);
    }finally{
      if(mounted&&first.revision==revision)setState(()=>loading=false);
    }
  }
  Future<Map<Object?,Object?>> preview(String id)async{
    final existing=previews.remove(id);
    if(existing!=null){previews[id]=existing;return existing;}
    final result=await widget.host.explorerPreview(id);
    if(mounted&&result['deferred']!=true&&id.startsWith('${first.revision}:')){
      previews[id]=result;
      while(previews.length>96){previews.remove(previews.keys.first);}
    }
    return result;
  }
  @override
  void dispose(){scroll.dispose();crumbScroll.dispose();super.dispose();}
  @override
  Widget build(BuildContext context){
    final raw=Map<Object?,Object?>.from(widget.data['selection'] as Map? ?? {});
    final selected=Set<int>.from(raw['indices'] as List? ?? []);
    final inverted=raw['inverted']==true,selecting=(raw['count'] as int? ?? 0)>0;
    final layout=widget.data['layout'] as int? ?? 0;
    final crumbs=(widget.data['crumbs'] as List? ?? []).cast<Map>();
    final quota=widget.data['quota'] as String? ?? '';
    final colors=Theme.of(context).colorScheme;
    final operations=Map<Object?,Object?>.from(widget.data['operations'] as Map? ?? {});
    Widget row(BuildContext context,int i){
      if(i==rows.length){
        return Center(child:loading?const Padding(padding:EdgeInsets.all(16),child:CircularProgressIndicator()):
          TextButton(key:const ValueKey('explorer-more'),onPressed:loadMore,
            child:Text(pageError?t('refresh'):'${t('more')} ($next / $total)')));
      }
      final data=rows[i],id=data['id'] as String;
      return ExplorerTile(key:ValueKey(id),row:data,layout:layout,
        selected:inverted?!selected.contains(i):selected.contains(i),
        labels:widget.labels,preview:preview,
        onTap:widget.busy?null:()=>widget.action(selecting?'entrySelect':'entryOpen',id),
        onLongPress:widget.busy||data['selectable']!=true?null:()=>widget.action('entrySelect',id));
    }
    return Column(children:[
      if(selecting&&operations.isNotEmpty)
        FileSelectionHeader(data:operations,labels:widget.labels,revision:first.revision,
          busy:widget.busy,action:widget.action),
      if(crumbs.isNotEmpty)
        SizedBox(height:48,child:ListView(controller:crumbScroll,scrollDirection:Axis.horizontal,
            padding:const EdgeInsets.symmetric(horizontal:10),children:[
            for(var i=0;i<crumbs.length;i++)...[
              if(i>0)const Icon(Icons.chevron_right,size:17),
              Padding(padding:const EdgeInsets.symmetric(vertical:4,horizontal:2),
                child:TextButton(key:ValueKey('crumb-$i'),
                  style:TextButton.styleFrom(backgroundColor:i==crumbs.length-1?colors.secondaryContainer:null),
                  onPressed:widget.busy||selecting||i==crumbs.length-1?null:
                    ()=>widget.action('crumb',crumbs[i]['id'] as String),
                  child:Text(crumbs[i]['label'] as String))),
            ],
          ])),
      if(quota.isNotEmpty)Padding(padding:const EdgeInsets.symmetric(horizontal:12),child:Text(quota,
        style:Theme.of(context).textTheme.bodySmall)),
      Row(children:[
        const SizedBox(width:14),
        Expanded(child:Text('$total ${t('items')}',style:Theme.of(context).textTheme.bodySmall)),
      ]),
      if(rows.isEmpty)Expanded(child:Center(child:Text(t('empty'))))
      else Expanded(child:layout==1
        ? GridView.builder(controller:scroll,padding:const EdgeInsets.fromLTRB(10,0,10,73),
          gridDelegate:SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount:(widget.data['columns'] as int? ?? 3).clamp(2,8),
            mainAxisExtent:150,crossAxisSpacing:4,mainAxisSpacing:5),
          itemCount:rows.length+(more?1:0),itemBuilder:row)
        : ListView.separated(controller:scroll,padding:const EdgeInsets.fromLTRB(10,0,10,73),
          itemCount:rows.length+(more?1:0),itemBuilder:row,
          separatorBuilder:(_,_)=>const FileRowDivider())),
      if(operations.isNotEmpty)
        FileOperationBar(data:operations,labels:widget.labels,revision:first.revision,
          busy:widget.busy,action:widget.action),
    ]);
  }
}

class ExplorerTile extends StatefulWidget {
  const ExplorerTile({super.key,required this.row,required this.layout,
    required this.selected,required this.labels,required this.preview,
    required this.onTap,required this.onLongPress});
  final Map<Object?,Object?> row;
  final int layout;
  final bool selected;
  final Map<String,String> labels;
  final Future<Map<Object?,Object?>> Function(String) preview;
  final VoidCallback? onTap,onLongPress;
  @override
  State<ExplorerTile> createState()=>_ExplorerTileState();
}
class _ExplorerTileState extends State<ExplorerTile>{
  Map<Object?,Object?>? details;
  String t(String key)=>widget.labels[key]??key;
  @override
  void initState(){super.initState();if(widget.row['preview']==true)load();}
  @override
  void didUpdateWidget(covariant ExplorerTile oldWidget){
    super.didUpdateWidget(oldWidget);
    if(oldWidget.row['preview']!=true&&widget.row['preview']==true)load();
  }
  Future<void> load()async{
    final id=widget.row['id'] as String;
    for(var attempt=0;attempt<40&&mounted;attempt++){
      try{
        final result=await widget.preview(id);
        if(!mounted)return;
        if(result['deferred']!=true){setState(()=>details=result);return;}
        await Future<void>.delayed(Duration(milliseconds:400+50*attempt));
      }catch(_){await Future<void>.delayed(const Duration(milliseconds:600));}
    }
    if(mounted&&widget.row['directory']==true){
      setState(()=>details={'secondary':t('countUnknown')});
    }
  }
  @override
  Widget build(BuildContext context){
    final row=widget.row;
    final colors=Theme.of(context).colorScheme;
    final kind=row['kind'] as String,grid=widget.layout==1;
    final size=grid?48.0:38.0;
    final image=details?['image'];
    Widget art=FileTypeIcon(kind:kind,size:size);
    if(image is Uint8List&&image.isNotEmpty){
      art=ClipRRect(borderRadius:BorderRadius.circular(5),child:Image.memory(image,width:size,height:size,
        fit:BoxFit.cover,errorBuilder:(_,_,_)=>FileTypeIcon(kind:kind,size:size)));
    }
    if(kind=='video'&&image is Uint8List){
      art=Stack(alignment:Alignment.center,children:[art,const Icon(Icons.play_circle_fill,color:Colors.white,size:20)]);
    }
    final reading=row['reading'] as int? ?? -1;
    final purpose=row['purpose'] as int? ?? 0;
    final subtitles=(details?['subtitles']??row['subtitles']) as int? ?? 0;
    final tags=[if(reading>=0)'$reading% ${t('reading')}',
      if((purpose&1)!=0)t('videoFolder'),if((purpose&2)!=0)t('readingFolder'),
      if((subtitles&1)!=0)t('external'),if((subtitles&2)!=0)t('embedded')];
    final secondary=(details?['secondary']??row['secondary']) as String? ?? '';
    final date=row['date'] as String? ?? '';
    final metadata=Text([secondary,if(!grid&&date.isNotEmpty)date].join('  ·  '),
      maxLines:1,overflow:TextOverflow.ellipsis,style:Theme.of(context).textTheme.bodySmall);
    final name=Text(row['name'] as String,maxLines:grid||widget.layout==2?2:1,
      overflow:TextOverflow.ellipsis,textAlign:grid?TextAlign.center:TextAlign.start,
      style:TextStyle(fontWeight:FontWeight.w600,fontSize:grid?12:14));
    final labels=Text(tags.join(' · '),maxLines:1,overflow:TextOverflow.ellipsis,
      style:TextStyle(color:colors.primary,fontSize:10,fontWeight:FontWeight.w600));
    return Material(color:widget.selected?colors.secondaryContainer:colors.surface,
      borderRadius:BorderRadius.circular(grid?8:0),child:InkWell(
        onTap:widget.onTap,onLongPress:widget.onLongPress,
        child:Padding(padding:EdgeInsets.all(grid?5:8),child:grid
          ? Column(mainAxisAlignment:MainAxisAlignment.center,children:[
            art,const SizedBox(height:5),name,metadata,if(tags.isNotEmpty)labels])
          : Row(children:[
            art,const SizedBox(width:12),Expanded(child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
              name,metadata,if(tags.isNotEmpty)labels])),
            if(widget.selected)Icon(Icons.check_circle,color:colors.primary,size:21)
            else if(row['directory']==true)const Icon(Icons.chevron_right,size:18),
          ])),
      ));
  }
}


/// A subtle, theme-aware boundary shared by file and favorite list rows.
class FileRowDivider extends StatelessWidget {
  const FileRowDivider({super.key});
  @override
  Widget build(BuildContext context) => Divider(
    height:1,thickness:1,
    color:Theme.of(context).colorScheme.onSurface.withValues(alpha:
      Theme.of(context).brightness==Brightness.dark ? .14 : .10));
}

// Display only. The native explorer supplies the category for every storage.
class FileTypeIcon extends StatelessWidget {
  const FileTypeIcon({super.key,required this.kind,required this.size});
  final String kind;
  final double size;
  @override
  Widget build(BuildContext context){
    final primary=Theme.of(context).colorScheme.primary;
    final (icon,color,mark)=switch(kind){
      'folder'=>(Icons.folder_rounded,const Color(0xffe5ad5b),''),
      'favorite'=>(Icons.star_rounded,const Color(0xffd8a34e),''),
      'image'=>(Icons.image_outlined,const Color(0xffab79c6),''),
      'video'=>(Icons.movie_outlined,const Color(0xffdd7662),''),
      'audio'=>(Icons.music_note,const Color(0xff4ba5a0),''),
      'spreadsheet'=>(Icons.table_chart_outlined,const Color(0xff258b5b),'X'),
      'word'=>(Icons.description_outlined,const Color(0xff397dd0),'W'),
      'presentation'=>(Icons.slideshow_outlined,const Color(0xffdc7044),'P'),
      'hangul'=>(Icons.article_outlined,const Color(0xff3b95c8),'HWP'),
      'pdf'=>(Icons.picture_as_pdf_outlined,const Color(0xffd85151),'PDF'),
      'text'=>(Icons.notes_rounded,const Color(0xff8396a8),'TXT'),
      'archive'=>(Icons.folder_zip_outlined,const Color(0xffc29445),'ZIP'),
      'book'=>(Icons.menu_book_outlined,const Color(0xff9471c9),''),
      'comic'=>(Icons.collections_bookmark_outlined,const Color(0xffb075c6),''),
      'android'=>(Icons.android,const Color(0xff65a94e),'APK'),
      'application'=>(Icons.apps_outlined,const Color(0xff7892b5),'EXE'),
      'subtitle'=>(Icons.subtitles_outlined,const Color(0xff4ba5a0),'CC'),
      'code'=>(Icons.code_rounded,const Color(0xff7892b5),''),
      'design'=>(Icons.brush_outlined,const Color(0xffe49a45),'ART'),
      _=>(Icons.insert_drive_file_outlined,primary,''),
    };
    return Container(width:size,height:size,decoration:BoxDecoration(
      color:color.withValues(alpha:.12),borderRadius:BorderRadius.circular(size*.20)),
      child:Column(mainAxisAlignment:MainAxisAlignment.center,children:[
        Icon(icon,size:mark.isEmpty?size*.67:size*.54,color:color),
        if(mark.isNotEmpty)Text(mark,style:TextStyle(color:color,fontSize:size*.22,
          fontWeight:FontWeight.w700,height:1.1)),
      ]));
  }
}
