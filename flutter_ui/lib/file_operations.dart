import 'package:material_ui/material_ui.dart';

typedef FileAction=Future<void> Function(String,[String?]);
abstract interface class FilePromptHost{
  Future<void> submitFilePrompt(String nonce,String value);
}

class FileSelectionHeader extends StatelessWidget{
  const FileSelectionHeader({super.key,required this.data,required this.labels,
    required this.revision,required this.busy,required this.action});
  final Map<Object?,Object?> data;
  final Map<String,String> labels;
  final String revision;
  final bool busy;
  final FileAction action;
  String t(String key)=>labels[key]??key;
  @override
  Widget build(BuildContext context){
    final selected=data['selected'] as int? ?? 0,total=data['selectable'] as int? ?? 0;
    final allowed=Set<String>.from(data['allowed'] as List? ?? []);
    const white=Colors.white;
    return Material(color:const Color(0xff003544),child:Row(children:[
      IconButton(key:const ValueKey('operation-clearSelection'),tooltip:t('clearSelection'),
        icon:const Icon(Icons.close,color:white),
        onPressed:busy||!allowed.contains('clearSelection')?null:()=>action('clearSelection',revision)),
      Expanded(child:Text('$selected / $total',style:const TextStyle(color:white,fontSize:18,fontWeight:FontWeight.w600))),
      IconButton(key:const ValueKey('operation-selectAll'),tooltip:t(selected==total?'clearAll':'selectAll'),
        icon:Icon(selected==total?Icons.check_box:Icons.check_box_outline_blank,color:white),
        onPressed:busy||!allowed.contains('selectAll')?null:()=>action('selectAll',revision)),
    ]));
  }
}

class FileOperationBar extends StatelessWidget{
  const FileOperationBar({super.key,required this.data,required this.labels,
    required this.revision,required this.busy,required this.action});
  final Map<Object?,Object?> data;
  final Map<String,String> labels;
  final String revision;
  final bool busy;
  final FileAction action;
  String t(String key)=>labels[key]??key;
  @override
  Widget build(BuildContext context){
    final selected=data['selected'] as int? ?? 0,staged=data['staged'] as int? ?? 0;
    final allowed=Set<String>.from(data['allowed'] as List? ?? []);
    if(selected==0&&(staged==0||data['destination']!=true))return const SizedBox.shrink();
    Widget control(String cmd,String label,IconData icon){
      final enabled=!busy&&allowed.contains(cmd);
      final color=enabled?Colors.white:Colors.white38;
      return Expanded(child:Semantics(button:true,enabled:enabled,label:t(label),
        child:InkWell(key:ValueKey('operation-$cmd'),onTap:enabled?()=>action(cmd,revision):null,
          child:Padding(padding:const EdgeInsets.symmetric(vertical:9,horizontal:2),
            child:Column(mainAxisSize:MainAxisSize.min,children:[
              Icon(icon,color:color,size:23),const SizedBox(height:5),
              Text(t(label),maxLines:2,overflow:TextOverflow.ellipsis,textAlign:TextAlign.center,
                style:TextStyle(fontSize:11,color:color)),
            ])))));
    }
    return Material(color:const Color(0xff003544),child:SafeArea(top:false,bottom:false,
      child:Row(children:selected>0?[
        control('copySelected','copy',Icons.copy_outlined),
        control('moveSelected','move',Icons.drive_file_move_outline),
        control('renameSelected','rename',Icons.edit_outlined),
        control('deleteSelected','delete',Icons.delete_outline),
        control('selectedMore','operationMore',Icons.more_vert),
      ]:[
        control('clearClipboard','cancel',Icons.close),
        Expanded(flex:3,child:Text('$staged ${t('items')} · ${t(data['moving']==true?'moveWaiting':'copyWaiting')}',
          textAlign:TextAlign.center,maxLines:2,overflow:TextOverflow.ellipsis,
          style:const TextStyle(color:Colors.white,fontSize:13))),
        control('pasteClipboard','paste',Icons.content_paste),
      ])));
  }
}

class TransferPanel extends StatelessWidget{
  const TransferPanel({super.key,required this.data,required this.labels,required this.busy,required this.action});
  final Map<Object?,Object?> data;
  final Map<String,String> labels;
  final bool busy;
  final FileAction action;
  String t(String key)=>labels[key]??key;
  @override
  Widget build(BuildContext context){
    final active=data['active']==true,cancelling=data['cancelling']==true;
    final raw=data['percent'] as int? ?? -1;
    final percent=raw<0?-1:raw.clamp(0,data['finished']==true&&!active?100:99);
    final name=data['name'] as String? ?? '';
    final amount=data['amount'] as String? ?? '';
    final colors=Theme.of(context).colorScheme;
    return SingleChildScrollView(padding:const EdgeInsets.fromLTRB(20,18,20,12),child:Column(
      crossAxisAlignment:CrossAxisAlignment.stretch,children:[
        Text(t(data['moving']==true?'moving':'copying'),style:Theme.of(context).textTheme.titleLarge),
        const SizedBox(height:16),
        Text(cancelling?'${t('cancelling')} $name':name.isEmpty?t('preparing'):name,
          maxLines:2,overflow:TextOverflow.ellipsis,style:Theme.of(context).textTheme.bodyMedium),
        const SizedBox(height:12),
        LinearProgressIndicator(key:const ValueKey('transfer-progress'),value:percent<0?null:percent/100,minHeight:8),
        const SizedBox(height:12),
        Row(children:[
          Text(percent<0?'--%':'$percent%',key:const ValueKey('transfer-percent'),
            style:TextStyle(fontWeight:FontWeight.w700,fontSize:24,color:colors.primary)),
          const SizedBox(width:12),Expanded(child:Text(amount,textAlign:TextAlign.end,maxLines:2)),
        ]),
        const SizedBox(height:6),
        Text('${data['completed']??0} / ${data['count']??0} ${t('items')}'),
        const SizedBox(height:12),
        Row(children:[
          Expanded(child:OutlinedButton(key:const ValueKey('transfer-hide'),
            onPressed:busy?null:()=>action('transferHide'),child:Text(t('hide')))),
          const SizedBox(width:8),
          Expanded(child:FilledButton(key:const ValueKey('transfer-cancel'),
            onPressed:busy||!active||cancelling?null:()=>action('transferCancel'),
            child:Text(t(cancelling?'cancelling':'cancel')))),
        ]),
      ]));
  }
}

class FilePromptPanel extends StatefulWidget{
  const FilePromptPanel({super.key,required this.data,required this.labels,required this.host,required this.action});
  final Map<Object?,Object?> data;
  final Map<String,String> labels;
  final FilePromptHost host;
  final FileAction action;
  @override
  State<FilePromptPanel> createState()=>_FilePromptPanelState();
}
class _FilePromptPanelState extends State<FilePromptPanel>{
  late final TextEditingController value;
  bool busy=false,failed=false;
  bool get editing=>widget.data['initial'] is String;
  String t(String key)=>widget.labels[key]??key;
  @override
  void initState(){
    super.initState();
    value=TextEditingController(text:widget.data['initial'] as String? ?? '');
    value.selection=TextSelection(baseOffset:0,extentOffset:value.text.length);
  }
  @override
  void dispose(){value.dispose();super.dispose();}
  Future<void> submit()async{
    if(busy||(editing&&value.text.trim().isEmpty))return;
    FocusScope.of(context).unfocus();
    setState((){busy=true;failed=false;});
    try{
      await widget.host.submitFilePrompt(widget.data['nonce'] as String,editing?value.text:'');
    }catch(_){
      if(mounted)setState((){busy=false;failed=true;});
    }
  }
  @override
  Widget build(BuildContext context){
    final message=widget.data['message'] as String? ?? '';
    return SingleChildScrollView(padding:const EdgeInsets.all(20),child:Column(
      crossAxisAlignment:CrossAxisAlignment.stretch,children:[
        Text(widget.data['title'] as String? ?? '',style:Theme.of(context).textTheme.titleLarge),
        if(message.isNotEmpty)...[const SizedBox(height:12),Text(message)],
        if(editing)...[
          const SizedBox(height:16),
          TextField(key:const ValueKey('file-prompt-input'),controller:value,enabled:!busy,
            // Android IMEs may disable Korean/Chinese when suggestions are forbidden.
             // Keep composition and language switching while preventing auto-correction.
             maxLines:1,keyboardType:TextInputType.text,
             autocorrect:false,enableSuggestions:true,enableIMEPersonalizedLearning:false,
             smartDashesType:SmartDashesType.disabled,smartQuotesType:SmartQuotesType.disabled,
             onChanged:(_)=>setState((){}),
            onSubmitted:(_)=>submit(),decoration:InputDecoration(labelText:t('rename'),border:const OutlineInputBorder())),
        ],
        if(failed)Padding(padding:const EdgeInsets.only(top:8),child:Text(t('error'),
          style:TextStyle(color:Theme.of(context).colorScheme.error))),
        const SizedBox(height:16),
        Row(children:[
          if(widget.data['cancel']==true)...[
            Expanded(child:OutlinedButton(key:const ValueKey('file-prompt-cancel'),
              onPressed:busy?null:()=>widget.action('promptCancel'),child:Text(t('cancel')))),
            const SizedBox(width:8),
          ],
          Expanded(child:FilledButton(key:const ValueKey('file-prompt-confirm'),
            onPressed:busy||(editing&&value.text.trim().isEmpty)?null:submit,
            child:Text(widget.data['positive'] as String? ?? t('confirm')))),
        ]),
      ]));
  }
}
