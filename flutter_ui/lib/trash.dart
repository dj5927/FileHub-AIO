import 'package:material_ui/material_ui.dart';
import 'explorer.dart' show FileTypeIcon;
import 'compact_text.dart';

abstract interface class TrashHost {
  Future<Map<Object?,Object?>> trashCommand(String revision,String command,List<String> ids);
}

/// UI-only list. Native journal retains paths and owns all restore/purge work.
class TrashScreen extends StatefulWidget {
  const TrashScreen({super.key,required this.data,required this.labels,required this.host});
  final Map<Object?,Object?> data;
  final Map<String,String> labels;
  final TrashHost host;
  @override
  State<TrashScreen> createState()=>_TrashScreenState();
}

class _TrashScreenState extends State<TrashScreen>{
  late Map<Object?,Object?> model;
  final selected=<String>{};
  bool submitting=false;
  String error='';
  String t(String key)=>widget.labels[key]??key;
  bool get busy=>submitting||model['loading']==true;
  List<Map<Object?,Object?>> get rows=>(model['rows'] as List? ?? const [])
      .map((e)=>Map<Object?,Object?>.from(e as Map)).toList(growable:false);
  Set<String> get available=>rows.where((r)=>r['available']==true)
      .map((r)=>r['id'] as String).toSet();
  @override
  void initState(){super.initState();model=widget.data;}
  @override
  void didUpdateWidget(covariant TrashScreen oldWidget){
    super.didUpdateWidget(oldWidget);
    if(oldWidget.data['revision']!=widget.data['revision']||
        oldWidget.data['loading']!=widget.data['loading']){
      model=widget.data;selected.retainAll(available);
    }
  }
  void toggle(String id){
    if(busy||!available.contains(id))return;
    setState(()=>selected.contains(id)?selected.remove(id):selected.add(id));
  }
  void toggleAll(){
    if(busy)return;
    final ids=available;
    setState((){if(selected.containsAll(ids)){selected.clear();}else{selected.addAll(ids);}});
  }
  Future<void> run(String command,Set<String> ids)async{
    if(busy)return;
    final revision=model['revision'] as String? ?? '';
    if(revision.isEmpty)return;
    setState((){submitting=true;error='';});
    try{
      final next=await widget.host.trashCommand(revision,command,ids.toList(growable:false));
      if(!mounted)return;
      setState((){model=next;selected.clear();error=next['error'] as String? ?? '';});
    }catch(_){
      if(mounted)setState(()=>error=t('error'));
    }finally{
      if(mounted)setState(()=>submitting=false);
    }
  }
  Future<void> confirm(String command)async{
    final ids=command=='empty'?available:Set<String>.of(selected);
    if(busy||ids.isEmpty)return;
    final revision=model['revision'];
    final restoring=command=='restore';
    final label=t(restoring?'restore':command=='empty'?'empty':'erase');
    final msg=t(restoring?'restoreConfirm':'eraseConfirm').replaceAll('%d',ids.length.toString());
    final approved=await showDialog<bool>(context:context,builder:(ctx)=>AlertDialog(
      title:Text(label),content:Text(msg),
      actions:[TextButton(onPressed:()=>Navigator.of(ctx).pop(false),child:Text(t('cancel'))),
        FilledButton(onPressed:()=>Navigator.of(ctx).pop(true),
          style:restoring?null:FilledButton.styleFrom(backgroundColor:Theme.of(ctx).colorScheme.error,
            foregroundColor:Theme.of(ctx).colorScheme.onError),child:Text(label))]));
    if(mounted&&approved==true&&revision==model['revision'])await run(command,ids);
  }
  Widget action(String name,IconData icon,bool enabled,VoidCallback handler)=>Expanded(
    child:InkWell(key:ValueKey('trash-$name'),onTap:enabled?handler:null,
      child:Semantics(button:true,enabled:enabled,label:t(name),
        child:Padding(padding:const EdgeInsets.symmetric(horizontal:3,vertical:8),
          child:Column(mainAxisSize:MainAxisSize.min,children:[
            Icon(icon,size:23,color:Colors.white.withValues(alpha:enabled?1:.36)),
            const SizedBox(height:4),
            Text(t(name),maxLines:2,overflow:TextOverflow.ellipsis,textAlign:TextAlign.center,
              style:TextStyle(fontSize:11,color:Colors.white.withValues(alpha:enabled?1:.36))),
          ])))));
  @override
  Widget build(BuildContext context){
    final colors=Theme.of(context).colorScheme,entries=rows,ids=available;
    final subtitle=model['summary'] as String? ?? '';
    final retention=model['retention'] as String? ?? '';
    return Column(children:[
      Material(color:colors.surface,child:Column(children:[
        Padding(padding:const EdgeInsets.fromLTRB(14,10,8,10),child:Row(children:[
          Expanded(child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
            Text(subtitle,maxLines:2,overflow:TextOverflow.ellipsis,
              style:TextStyle(fontSize:14,fontWeight:FontWeight.w600,color:colors.onSurface)),
            const SizedBox(height:3),
            Text(retention,maxLines:2,overflow:TextOverflow.ellipsis,
              style:TextStyle(fontSize:11,color:colors.onSurfaceVariant)),
          ])),
          IconButton(key:const ValueKey('trash-refresh'),tooltip:t('refresh'),
            onPressed:busy?null:()=>run('refresh',<String>{}),icon:const Icon(Icons.refresh,size:22)),
        ])),
        if(busy)const LinearProgressIndicator(minHeight:2)else const Divider(height:1),
        if(entries.isNotEmpty)Row(children:[
          SizedBox(width:48,height:44,child:Checkbox(
            key:const ValueKey('trash-select-all'),value:ids.isNotEmpty&&selected.containsAll(ids),
            onChanged:busy||ids.isEmpty?null:(_)=>toggleAll())),
          Expanded(child:CompactText(selected.isEmpty?t('selectAll'):
            t('selectedCount').replaceAll('%d',selected.length.toString()),
            style:TextStyle(fontSize:12,color:colors.onSurfaceVariant))),
        ]),
      ])),
      if(error.isNotEmpty)MaterialBanner(content:Text(error),
        actions:[TextButton(onPressed:()=>setState(()=>error=''),child:Text(t('ok')))]),
      Expanded(child:entries.isEmpty
        ? Center(child:Padding(padding:const EdgeInsets.all(24),child:Column(
            mainAxisSize:MainAxisSize.min,children:[
              Icon(Icons.delete_outline_rounded,size:52,color:colors.onSurfaceVariant.withValues(alpha:.55)),
              const SizedBox(height:12),
              Text(busy?t('loading'):t('emptyMessage'),textAlign:TextAlign.center,
                style:TextStyle(color:colors.onSurfaceVariant,fontSize:14)),
            ])))
        : ListView.separated(key:const ValueKey('trash-list'),
            itemCount:entries.length,separatorBuilder:(_,_)=>const Divider(height:1),
            itemBuilder:(context,index){
              final row=entries[index],id=row['id'] as String,checked=selected.contains(id);
              final enabled=row['available']==true&&!busy;
              return Material(color:checked?colors.primaryContainer.withValues(alpha:.55):colors.surface,
                child:InkWell(key:ValueKey('trash-entry-$id'),onTap:enabled?()=>toggle(id):null,
                  child:Padding(padding:const EdgeInsets.symmetric(horizontal:10,vertical:10),
                    child:Row(children:[
                      FileTypeIcon(kind:row['icon'] as String? ?? 'file',size:42),
                      const SizedBox(width:10),
                      Expanded(child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
                        Text(row['name'] as String? ?? '',maxLines:2,overflow:TextOverflow.ellipsis,
                          style:TextStyle(fontSize:14,fontWeight:FontWeight.w500,color:colors.onSurface)),
                        const SizedBox(height:3),
                        Text(row['origin'] as String? ?? '',maxLines:1,overflow:TextOverflow.ellipsis,
                          style:TextStyle(fontSize:11,color:colors.onSurfaceVariant)),
                        const SizedBox(height:2),
                        Text(row['detail'] as String? ?? '',maxLines:2,overflow:TextOverflow.ellipsis,
                          style:TextStyle(fontSize:11,color:row['available']==true?colors.onSurfaceVariant:colors.error)),
                      ])),
                      Checkbox(value:checked,onChanged:enabled?(_)=>toggle(id):null,
                        key:ValueKey('trash-check-$id')),
                    ]))));
            })),
      Material(color:const Color(0xff003544),child:Row(children:[
        action('restore',Icons.restore_rounded,!busy&&selected.isNotEmpty,()=>confirm('restore')),
        action('erase',Icons.delete_forever_outlined,!busy&&selected.isNotEmpty,()=>confirm('erase')),
        action('empty',Icons.delete_sweep_outlined,!busy&&ids.isNotEmpty,()=>confirm('empty')),
      ])),
    ]);
  }
}
