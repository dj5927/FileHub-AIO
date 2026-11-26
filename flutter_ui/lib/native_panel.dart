import 'compact_text.dart';
import 'package:material_ui/material_ui.dart';

abstract interface class NativePanelHost {
  Future<void> panelCommand(String revision,String id,[int? value]);
}

/// Native callbacks own all actual settings and document operations.
class NativePanel extends StatefulWidget {
  const NativePanel({super.key,required this.data,required this.labels,
    required this.host,required this.busy,required this.reload});
  final Map<Object?,Object?> data;
  final Map<String,String> labels;
  final NativePanelHost host;
  final bool busy;
  final Future<void> Function() reload;
  @override
  State<NativePanel> createState()=>_NativePanelState();
}

class _NativePanelState extends State<NativePanel> {
  bool sending=false;
  bool failed=false;
  String t(String key)=>widget.labels[key]??key;
  bool get enabled=>!sending&&!widget.busy;

  Future<void> send(String revision,String id,[int? value])async{
    if(!enabled||widget.data['revision']!=revision)return;
    setState((){sending=true;failed=false;});
    try{
      await widget.host.panelCommand(revision,id,value);
    }catch(_){
      if(mounted){setState(()=>failed=true);await widget.reload();}
    }finally{
      if(mounted)setState(()=>sending=false);
    }
  }

  Future<void> choice(String revision,Map<Object?,Object?> row)async{
    if(!enabled)return;
    final options=List<String>.from(row['choices'] as List? ?? []);
    if(options.isEmpty)return;
    var selected=(row['selected'] as int? ?? 0).clamp(0,options.length-1);
    final index=await showDialog<int>(context:context,builder:(dialogContext)=>
      StatefulBuilder(builder:(context,update)=>AlertDialog(
        title:CompactText(row['title'] as String? ?? ''),
        content:ConstrainedBox(constraints:const BoxConstraints(maxWidth:440),
          child:SingleChildScrollView(child:Column(mainAxisSize:MainAxisSize.min,
            children:[for(var i=0;i<options.length;i++)
              ListTile(key:ValueKey('panel-choice-$i'),title:CompactText(options[i]),
                trailing:Icon(i==selected?Icons.radio_button_checked:Icons.radio_button_off),
                selected:i==selected,onTap:()=>update(()=>selected=i)),
            ]))),
        actions:[
          TextButton(onPressed:()=>Navigator.pop(dialogContext),child:Text(t('cancel'))),
          FilledButton(key:const ValueKey('panel-apply'),
            onPressed:()=>Navigator.pop(dialogContext,selected),child:Text(t('apply'))),
        ])));
    if(mounted&&index!=null)await send(revision,row['id'] as String,index);
  }

  @override
  Widget build(BuildContext context){
    final revision=widget.data['revision'] as String;
    final tabs=List<String>.from(widget.data['tabs'] as List? ?? []);
    final selected=widget.data['selected'] as int? ?? 0;
    final rows=(widget.data['rows'] as List? ?? []).map((r)=>Map<Object?,Object?>.from(r as Map)).toList();
    final title=widget.data['title'] as String? ?? '';
    final theme=Theme.of(context);
    final colors=theme.colorScheme;
    Widget item(Map<Object?,Object?> row){
      final id=row['id'] as String;
      final kind=row['kind'];
      final heading=row['title'] as String? ?? '';
      final detail=row['detail'] as String? ?? '';
      if(kind=='section'){
        return Padding(padding:const EdgeInsets.fromLTRB(4,20,4,8),
          child:CompactText(heading,style:theme.textTheme.titleMedium?.copyWith(fontWeight:FontWeight.w700)));
      }
      if(kind=='note'){
        return Padding(padding:const EdgeInsets.fromLTRB(4,2,4,12),
          child:Text(detail,style:theme.textTheme.bodySmall?.copyWith(color:colors.onSurfaceVariant)));
      }
      final allowed=enabled&&row['enabled']==true;
      if(kind=='folder'){
        return Material(key:ValueKey('panel-$id'),color:colors.surface,child:InkWell(
          onTap:allowed?()=>send(revision,id):null,
          onLongPress:allowed?()=>send(revision,'$id:remove'):null,
          child:Padding(padding:const EdgeInsets.symmetric(horizontal:8,vertical:4),child:Row(children:[
            const Icon(Icons.folder_rounded,color:Color(0xffe5ad5b),size:38),
            const SizedBox(width:12),Expanded(child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
              Text(heading,maxLines:1,overflow:TextOverflow.ellipsis,
                style:const TextStyle(fontWeight:FontWeight.w600,fontSize:14)),
              if(detail.isNotEmpty)Text(detail,maxLines:1,overflow:TextOverflow.ellipsis,
                style:theme.textTheme.bodySmall),
            ])),
            IconButton(key:ValueKey('panel-remove-$id'),tooltip:t('removeRole'),
              onPressed:allowed?()=>send(revision,'$id:remove'):null,icon:const Icon(Icons.more_vert)),
          ])),
        ));
      }
      return Card(key:ValueKey('panel-$id'),margin:const EdgeInsets.only(bottom:8),elevation:0,
        color:colors.surface,shape:RoundedRectangleBorder(borderRadius:BorderRadius.circular(12),
          side:BorderSide(color:colors.outlineVariant)),
        child:ListTile(
          title:CompactText(heading,style:const TextStyle(fontWeight:FontWeight.w600)),
          subtitle:detail.isEmpty?null:Padding(padding:const EdgeInsets.only(top:4),child:CompactText(detail)),
          trailing:Icon(kind=='choice'?Icons.unfold_more:Icons.chevron_right),
          enabled:allowed,
          onTap:!allowed?null:kind=='choice'?()=>choice(revision,row):()=>send(revision,id),
        ));
    }
    return Material(color:theme.scaffoldBackgroundColor,child:Column(children:[
      if(title.isNotEmpty)Padding(padding:const EdgeInsets.fromLTRB(16,16,16,8),
        child:Align(alignment:Alignment.centerLeft,
          child:CompactText(title,style:theme.textTheme.titleLarge))),
      if(tabs.isNotEmpty)Material(color:colors.surface,child:SingleChildScrollView(
        scrollDirection:Axis.horizontal,padding:const EdgeInsets.symmetric(horizontal:12,vertical:4),
        child:Row(children:[for(var i=0;i<tabs.length;i++)
          Padding(padding:const EdgeInsets.only(right:6),child:ChoiceChip(
            key:ValueKey('panel-tab-$i'),label:CompactText(tabs[i]),selected:i==selected,
            onSelected:enabled&&selected!=i?(_)=>send(revision,'tab',i):null)),
        ]))),
      if(widget.busy||sending||widget.data['loading']==true)const LinearProgressIndicator(minHeight:2),
      if(failed)MaterialBanner(content:Text(t('error')),actions:[
        TextButton(onPressed:(){setState(()=>failed=false);widget.reload();},child:Text(t('refresh')))]),
      Expanded(child:Align(alignment:Alignment.topCenter,child:ConstrainedBox(
        constraints:const BoxConstraints(maxWidth:850),
        child:ListView(key:ValueKey('panel-page-$selected'),padding:const EdgeInsets.fromLTRB(10,8,10,85),
          children:rows.map(item).toList()),
      ))),
    ]));
  }
}
