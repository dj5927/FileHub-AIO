import 'package:material_ui/material_ui.dart';

abstract interface class ViewerHost {
  Future<void> viewerCommand(String nonce,String command,[int? value]);
}

/// A compact control strip only. The actual reader/player remains on Android.
class ViewerPanel extends StatefulWidget {
  const ViewerPanel({super.key,required this.data,required this.labels,required this.host});
  final Map<Object?,Object?> data;
  final Map<String,String> labels;
  final ViewerHost host;
  @override
  State<ViewerPanel> createState()=>_ViewerPanelState();
}
class _ViewerPanelState extends State<ViewerPanel> {
  bool sending=false;
  double? drag;
  String t(String key)=>widget.labels[key]??key;
  Set<String> get enabled=>Set<String>.from(widget.data['enabled'] as List? ?? []);
  Future<void> send(String command,[int? value])async{
    if(sending||!enabled.contains(command))return;
    setState(()=>sending=true);
    try{
      await widget.host.viewerCommand(widget.data['nonce'] as String,command,value);
    }catch(_){
      // Native readiness may change during a page turn or rotation.
      // Do not predict or advance the native page on a rejected command.
    }finally{
      if(mounted)setState(()=>sending=false);
    }
  }
  @override
  Widget build(BuildContext context){
    final data=widget.data;
    final mode=data['mode'] as String? ?? 'reader';
    final bg=Color(data['bg'] as int? ?? 0xff101923);
    final fg=Color(data['fg'] as int? ?? 0xffffffff);
    final header=mode=='image'||mode=='video'||mode=='document';
    final slider=mode=='readerSlider';
    final playing=data['playing']==true;
    final label=(data['label'] as String? ?? '')+
        (data['readOnly']==true?' · ${t('readOnly')}':data['busy']==true?' · ${t('saving')}':'');
    final position=(data['progress'] as int? ?? 0).clamp(0,1000);
    final allowed=enabled;
    final canSeek=allowed.contains('seek')&&!sending;
    Widget button(String command,IconData icon,{String? description}){
      final on=!sending&&allowed.contains(command);
      return SizedBox(width:header?40:44,height:double.infinity,
        child:IconButton(key:ValueKey('viewer-$command'),
          padding:EdgeInsets.zero,visualDensity:VisualDensity.compact,
          tooltip:description??t(command),icon:Icon(icon,size:21,color:on?fg:fg.withValues(alpha:.30)),
          onPressed:on?()=>send(command):null));
    }
    Widget caption(){
      final text=Text(drag==null?label:'${(drag!/10).round()}%',
        maxLines:header?1:2,overflow:TextOverflow.ellipsis,textAlign:header?TextAlign.start:TextAlign.center,
        style:TextStyle(color:fg,fontSize:header?14:13,fontWeight:FontWeight.w600));
      if(header)return Expanded(child:text);
      return Expanded(child:Semantics(button:true,label:t('quick'),
        child:InkWell(key:const ValueKey('viewer-quick'),
          onTap:allowed.contains('quick')&&!sending?()=>send('quick'):null,
          child:Center(child:text))));
    }
    return Material(color:bg,child:LayoutBuilder(builder:(context,box){
      final row=Row(children:header?[
        button('back',Icons.chevron_left),
        caption(),
        if(mode=='image'||allowed.contains('previous'))button('previous',Icons.chevron_left),
        if(mode=='image'||allowed.contains('next'))button('next',Icons.chevron_right),
        if(mode=='video')...[
          button('play',playing?Icons.pause:Icons.play_arrow,description:t(playing?'pause':'play')),
          button('settings',Icons.more_vert),
        ],
        if(mode=='document'&&data['hasSearch']==true)
          button('search',Icons.search),
        if(mode=='document'&&data['hasSave']==true)
          button('save',Icons.save_outlined),
      ]:[
        button('previous',Icons.chevron_left),caption(),button('next',Icons.chevron_right),
      ]);
      if(!slider)return row;
      final lower=(box.maxHeight*.42).clamp(25.0,34.0);
      return Column(children:[
        Expanded(child:row),
        SizedBox(height:lower,child:SliderTheme(data:SliderTheme.of(context).copyWith(
          trackHeight:3,thumbShape:const RoundSliderThumbShape(enabledThumbRadius:6),
          overlayShape:const RoundSliderOverlayShape(overlayRadius:12),
          activeTrackColor:const Color(0xff28a99b),thumbColor:const Color(0xff28a99b),
          inactiveTrackColor:fg.withValues(alpha:.20)),child:Slider(
            key:const ValueKey('viewer-seek'),value:drag??position.toDouble(),
            min:0,max:1000,
            onChangeStart:canSeek?(value)=>setState(()=>drag=value):null,
            onChanged:canSeek?(value)=>setState(()=>drag=value):null,
            onChangeEnd:canSeek?(value){
              setState(()=>drag=null);
              send('seek',value.round());
            }:null,
          ))),
      ]);
    }));
  }
}
