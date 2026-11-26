import 'compact_text.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';

abstract interface class RemoteEditorHost {
  Future<void> saveRemote(Map<String,Object?> draft);
  Future<bool> loginTeraBox();
  Future<void> cloudHelp(String protocol);
  Future<void> classicEditor();
}

const remoteProtocols=['SMB','FTP','SFTP','WebDAV','TeraBox','MYBOX','GoogleDrive','OneDrive'];
const remotePorts=[445,21,22,443,0,0,0,0];

class RemoteEditorScreen extends StatefulWidget {
  const RemoteEditorScreen({super.key,required this.data,required this.labels,
      required this.host,required this.onCancel,this.compact=false,this.onSaved});
  final Map<String,Object?> data;
  final Map<String,String> labels;
  final RemoteEditorHost host;
  final VoidCallback onCancel;
  final bool compact;
  final VoidCallback? onSaved;
  @override
  State<RemoteEditorScreen> createState()=>_RemoteEditorScreenState();
}

class _RemoteEditorScreenState extends State<RemoteEditorScreen> {
  late final TextEditingController name,host,port,root,username,secret;
  late String protocol,ftpCharset,sftpCharset,secretMode;
  late bool ssl,ftpPasv,readOnly,editing;
  late int ftpTls;
  bool busy=false,advanced=false,anonymous=false;
  bool get canAnonymous=>protocol=="SMB"||protocol=="FTP";
  String? error;
  bool get google=>protocol=='GoogleDrive';
  bool get oneDrive=>protocol=='OneDrive';
  bool get oauth=>google||oneDrive;
  String protocolTitle(String value)=>value=='GoogleDrive'?'Google Drive':value;
  String t(String key)=>widget.labels[key]??key;
  bool get ordinary=>remoteProtocols.indexOf(protocol)<4;
  String get secretLabel=>t(protocol=='TeraBox'?'ndus':protocol=='MYBOX'?'pat':'password');
  @override
  void initState(){
    super.initState();
    final d=widget.data;
    editing=d['editing']==true;
    protocol=d['protocol'] as String? ?? 'SMB';
    if(!remoteProtocols.contains(protocol))protocol='SMB';
    name=TextEditingController(text:d['name'] as String? ?? '');
    host=TextEditingController(text:d['host'] as String? ?? '');
    port=TextEditingController(text:(d['port']??445).toString());
    root=TextEditingController(text:d['root'] as String? ?? '');
    username=TextEditingController(text:d['username'] as String? ?? '');
    secret=TextEditingController();
    ssl=d['ssl']!=false;
    ftpPasv=d['ftpPasv']!=false;
    readOnly=d['readOnly']==true;
    ftpTls=d['ftpTls'] as int? ?? 0;
    ftpCharset=d['ftpCharset'] as String? ?? 'UTF-8';
    sftpCharset=d['sftpCharset'] as String? ?? 'UTF-8';
    secretMode=editing?'keep':'replace';
    anonymous=canAnonymous&&editing&&username.text.isEmpty&&d['hasSecret']!=true;
  }
  @override
  void dispose(){
    secret.clear();
    for(final c in [name,host,port,root,username,secret]){c.dispose();}
    super.dispose();
  }
  void changeProtocol(String? value){
    if(value==null||value==protocol)return;
    setState((){
      final wasCloud=!ordinary;
      protocol=value;
      anonymous=false;
      port.text=remotePorts[remoteProtocols.indexOf(value)].toString();
      if(value=='FTP'&&ftpTls==2)port.text='990';
      if(wasCloud||!ordinary){host.clear();username.clear();root.clear();}
      secret.clear();
      secretMode='replace';
      error=null;
    });
  }
  void changeEndpoint(){
    if(secretMode=='keep')setState((){secretMode='replace';error=null;});
  }
  void setTls(int? value){
    if(value==null||value==ftpTls)return;
    setState((){
      ftpTls=value;
      if(port.text=='21'||port.text=='990')port.text=value==2?'990':'21';
      if(secretMode=='keep')secretMode='replace';
    });
  }
  Map<String,Object?> draft()=>{
    'protocol':protocol,'name':name.text,'host':host.text,'port':port.text,
    'root':root.text,'username':anonymous?'':username.text,
    if(canAnonymous)'anonymous':anonymous,'ssl':ssl,'ftpTls':ftpTls,
    'ftpCharset':ftpCharset,'sftpCharset':sftpCharset,'ftpPasv':ftpPasv,
    'readOnly':readOnly,'secretMode':anonymous?'empty':secretMode,
    if(!anonymous&&secretMode=='replace')'secret':secret.text,
  };
  Future<void> run(Future<void> Function() fn)async{
    if(busy)return;
    FocusScope.of(context).unfocus();
    setState((){busy=true;error=null;});
    try{await fn();}
    on PlatformException catch(e){
      if(mounted)setState(()=>error=t(widget.labels.containsKey('err_${e.code}')?'err_${e.code}':'err_invalid'));
    }catch(_){if(mounted)setState(()=>error=t('err_saveFailed'));}
    finally{if(mounted)setState(()=>busy=false);}
  }
  Future<void> save()=>run(()async{
    await widget.host.saveRemote(draft());
    if(mounted){secret.clear();widget.onSaved?.call();}
  });
  Future<void> login()=>run(()async{
    if(await widget.host.loginTeraBox()&&mounted){
      setState((){secretMode='session';secret.clear();});
    }
  });
  Widget field(String key,TextEditingController controller,{String? hint,String? label,
      bool password=false,bool numeric=false,bool endpoint=false}){
    final input=TextField(key:ValueKey('editor-$key'),controller:controller,
      enabled:!busy&&!(anonymous&&(key=='username'||key=='secret')),
       obscureText:password,autocorrect:false,
       // Ordinary text must not request the password/no-suggestions IME layout.
       // Password/PAT/session fields stay masked and suggestion-free.
       enableSuggestions:!password&&!numeric,enableIMEPersonalizedLearning:false,
       smartDashesType:SmartDashesType.disabled,smartQuotesType:SmartQuotesType.disabled,
      style:TextStyle(fontSize:widget.compact?14:16),
      keyboardType:password?TextInputType.visiblePassword:numeric?TextInputType.number:TextInputType.text,
      inputFormatters:numeric?[FilteringTextInputFormatter.digitsOnly]:null,
      onChanged:endpoint?(_)=>changeEndpoint():null,
      decoration:InputDecoration(labelText:widget.compact?null:label??t(key),hintText:hint,
        isDense:widget.compact,contentPadding:widget.compact?const EdgeInsets.symmetric(vertical:8,horizontal:2):null,
        border:widget.compact?const UnderlineInputBorder():const OutlineInputBorder()));
    return Padding(padding:EdgeInsets.only(bottom:widget.compact?6:12),
      child:widget.compact?Row(crossAxisAlignment:CrossAxisAlignment.center,children:[
        SizedBox(width:91,child:CompactText(label??t(key),style:const TextStyle(fontSize:12))),
        const SizedBox(width:8),Expanded(child:input),
      ]):input);
  }
  Widget select<T>(String key,T current,List<T> values,List<String> labels,ValueChanged<T?> changed){
    final dropdown=DropdownButton<T>(
      key:ValueKey('editor-$key'),value:values.contains(current)?current:values.first,
      isExpanded:true,isDense:widget.compact,iconSize:18,itemHeight:48,
      style:TextStyle(fontSize:14,color:Theme.of(context).colorScheme.onSurface),
      onChanged:busy?null:changed,
      items:[for(var i=0;i<values.length;i++)DropdownMenuItem<T>(value:values[i],
        child:CompactText(labels[i]))]);
    if(widget.compact){return Padding(padding:const EdgeInsets.only(bottom:6),child:Row(children:[
      SizedBox(width:91,child:CompactText(t(key),style:const TextStyle(fontSize:12))),
      const SizedBox(width:8),Expanded(child:dropdown),
    ]));}
    return Padding(padding:const EdgeInsets.only(bottom:12),child:InputDecorator(
      decoration:InputDecoration(labelText:t(key),border:const OutlineInputBorder()),
      child:DropdownButtonHideUnderline(child:dropdown)));
  }
  Widget toggle(String key,bool value,ValueChanged<bool> changed)=>SwitchListTile(
    key:ValueKey('editor-$key'),contentPadding:EdgeInsets.zero,
    dense:widget.compact,visualDensity:widget.compact?VisualDensity.compact:VisualDensity.standard,
    title:CompactText(t(key),style:TextStyle(fontSize:widget.compact?12:14)),
    value:value,onChanged:busy?null:changed);
  List<String> options(String key,List<String> fallback)=>
      (widget.data[key] as List?)?.cast<String>()??fallback;
  List<Widget> fields(){
    final ftp=options('ftpOptions',['UTF-8']);
    final sftp=options('sftpOptions',['UTF-8']);
    if(oauth){return [
      field('name',name),
      if(editing&&username.text.isNotEmpty)Text(username.text,maxLines:1,overflow:TextOverflow.ellipsis),
      Padding(padding:const EdgeInsets.symmetric(vertical:12),child:Text(t(oneDrive?(editing?'oneDriveReconnect':'oneDriveHint'):(editing?'googleReconnect':'googleHint')),style:const TextStyle(fontSize:13))),
      toggle('readOnly',readOnly,(v)=>setState(()=>readOnly=v)),
    ];}
    final available=editing?remoteProtocols.where((p)=>p!='GoogleDrive'&&p!='OneDrive').toList():remoteProtocols;
    return [
      field('name',name),
      if(!widget.compact||editing)select<String>('protocol',protocol,available,available.map(protocolTitle).toList(),changeProtocol),
      if(ordinary)...[
        field('host',host,hint:t('hostHint'),endpoint:true),
        field('port',port,numeric:true,endpoint:true),
      ],
      if(protocol=='WebDAV'&&widget.compact)
        RadioGroup<bool>(groupValue:ssl,onChanged:(v)=>setState((){
          if(v==null||busy)return;
          if(port.text=='80'||port.text=='443')port.text=v?'443':'80';
          ssl=v;if(secretMode=='keep')secretMode='replace';
        }),child:Row(mainAxisAlignment:MainAxisAlignment.end,children:[
          for(final secure in [false,true]) ...[
            Radio<bool>(value:secure,enabled:!busy,visualDensity:VisualDensity.compact),
            Text(secure?'https':'http',style:const TextStyle(fontSize:13)),
          ],
        ])),
      if(ordinary)field('username',username,endpoint:true),
      if(editing&&secretMode=='keep'&&!anonymous)
        ListTile(dense:widget.compact,contentPadding:EdgeInsets.zero,
          leading:const Icon(Icons.lock_outline,size:18),title:CompactText(t('keep')),
          trailing:TextButton(key:const ValueKey('editor-replace'),onPressed:busy?null:()=>setState(()=>secretMode='replace'),child:Text(t('replace')))),
      if(secretMode=='replace'||anonymous)field('secret',secret,label:secretLabel,password:true),
      if(canAnonymous)toggle('anonymous',anonymous,(v)=>setState((){
        anonymous=v;username.clear();secret.clear();secretMode='replace';error=null;
      })),
      if(secretMode=='session')
        ListTile(dense:widget.compact,contentPadding:EdgeInsets.zero,leading:const Icon(Icons.check_circle_outline),title:CompactText(t('sessionReady')),
          trailing:TextButton(onPressed:busy?null:()=>setState(()=>secretMode='replace'),child:Text(t('replace')))),
      if(protocol=='SMB'||(!widget.compact&&protocol!='MYBOX'))
        field('root',root,hint:t(protocol=='SMB'?'rootSmb':protocol=='SFTP'?'rootSftp':'rootOther')),
      if(protocol=='TeraBox'||protocol=='MYBOX')...[
        Text(t(protocol=='TeraBox'?'cloudHint':'patHint'),style:const TextStyle(fontSize:12)),
        if(protocol=='TeraBox')OutlinedButton(key:const ValueKey('editor-login'),
          onPressed:busy?null:login,child:Text(t('login'))),
        TextButton(onPressed:busy?null:()=>run(()=>widget.host.cloudHelp(protocol)),
          child:Text(t(protocol=='TeraBox'?'pcHelp':'patHelp'))),
      ],
      if(widget.compact)
        TextButton.icon(key:const ValueKey('editor-advanced'),onPressed:busy?null:()=>setState(()=>advanced=!advanced),
          icon:Icon(advanced?Icons.expand_less:Icons.expand_more),label:Text(t('advanced'))),
      if(!widget.compact||advanced)...[
        if(widget.compact&&protocol!='SMB'&&protocol!='MYBOX')
          field('root',root,hint:t(protocol=='SFTP'?'rootSftp':'rootOther')),
        if(protocol=='WebDAV'&&!widget.compact)toggle('ssl',ssl,(v)=>setState((){ssl=v;if(secretMode=='keep')secretMode='replace';})),
        if(protocol=='FTP')...[
          select<int>('ftpTls',ftpTls,[0,1,2],[t('ftpPlain'),'FTPS Explicit TLS (21)','FTPS Implicit TLS (990)'],setTls),
          select<String>('ftpCharset',ftpCharset,ftp,options('ftpLabels',ftp),(v)=>setState(()=>ftpCharset=v??ftp.first)),
          toggle('ftpPasv',ftpPasv,(v)=>setState(()=>ftpPasv=v)),
        ],
        if(protocol=='SFTP')
          select<String>('sftpCharset',sftpCharset,sftp,options('sftpLabels',sftp),(v)=>setState(()=>sftpCharset=v??sftp.first)),
        if(ordinary&&!anonymous&&secretMode!='keep')
          toggle('emptySecret',secretMode=='empty',(v)=>setState((){secret.clear();secretMode=v?'empty':'replace';})),
        toggle('readOnly',readOnly,(v)=>setState(()=>readOnly=v)),
      ],
    ];
  }
  Widget errorLine()=>error==null?const SizedBox.shrink():Padding(
    padding:const EdgeInsets.only(bottom:6),child:Text(error!,style:TextStyle(color:Theme.of(context).colorScheme.error)));
  @override
  Widget build(BuildContext context){
    if(widget.compact){return AlertDialog(
      title:Row(children:[ProtocolIcon(protocol),const SizedBox(width:10),Expanded(child:Text(protocolTitle(protocol),style:const TextStyle(fontSize:18,fontWeight:FontWeight.w600)))]),
      titlePadding:const EdgeInsets.fromLTRB(20,18,20,8),
      contentPadding:const EdgeInsets.fromLTRB(20,4,20,4),
      insetPadding:const EdgeInsets.symmetric(horizontal:20,vertical:16),
      constraints:const BoxConstraints(maxWidth:480),
      content: SizedBox(width:400,child:SingleChildScrollView(child:Column(mainAxisSize:MainAxisSize.min,children:[
        if(busy)const LinearProgressIndicator(minHeight:2),errorLine(),...fields(),
      ]))),
      actions:[TextButton(onPressed:busy?null:widget.onCancel,child:Text(t('cancel'))),
        TextButton(key:const ValueKey('editor-save'),onPressed:busy?null:save,child:Text(t(oneDrive?'oneDriveConnect':google?'googleConnect':'save')))],
    );}
    return PopScope(canPop:!busy,child:Scaffold(
      appBar:AppBar(title:Text(t(editing?'editorEdit':'editorAdd')),
        leading:BackButton(onPressed:busy?null:widget.onCancel)),
      body:SafeArea(top:false,child:Column(children:[
        if(busy)const LinearProgressIndicator(),
        errorLine(),
        Expanded(child:Align(alignment:Alignment.topCenter,child:ConstrainedBox(
          constraints:const BoxConstraints(maxWidth:640),
          child:ListView(padding:const EdgeInsets.all(16),children:fields()),
        ))),
        Padding(padding:const EdgeInsets.fromLTRB(16,8,16,12),child:Row(children:[
          Expanded(child:OutlinedButton(onPressed:busy?null:widget.onCancel,child:Text(t('cancel')))),
          const SizedBox(width:10),
          Expanded(child:FilledButton(key:const ValueKey('editor-save'),onPressed:busy?null:save,child:Text(t(oneDrive?'oneDriveConnect':google?'googleConnect':'save')))),
        ])),
      ])),
    ));
  }
}

/// Official provider artwork; original aspect ratio is preserved.
String? cloudLogoAsset(String protocol)=>switch(protocol){
  'GoogleDrive'=>'assets/cloud/google_drive.png',
  'OneDrive'=>'assets/cloud/onedrive.png',
  'MYBOX'=>'assets/cloud/mybox.png',
  'TeraBox'=>'assets/cloud/terabox.png',
  _=>null,
};
class CloudLogo extends StatelessWidget {
  const CloudLogo(this.protocol,{super.key,required this.size,required this.fallback});
  final String protocol;
  final double size;
  final Widget fallback;
  @override
  Widget build(BuildContext context){
    final asset=cloudLogoAsset(protocol);
    if(asset==null)return fallback;
    return Image.asset(asset,width:size,height:size,fit:BoxFit.contain,
      filterQuality:FilterQuality.high,excludeFromSemantics:true,
      errorBuilder:(_,_,_)=>fallback);
  }
}

class ProtocolIcon extends StatelessWidget {
  const ProtocolIcon(this.protocol,{super.key});
  final String protocol;
  @override
  Widget build(BuildContext context){
    final (glyph,color)=switch(protocol){
      'SMB'=>(Icons.dns_outlined,const Color(0xff5687bd)),
      'FTP'=>(Icons.swap_horiz_rounded,const Color(0xffb58a42)),
      'SFTP'=>(Icons.lock_outline_rounded,const Color(0xff568b83)),
      'WebDAV'=>(Icons.language_rounded,const Color(0xff8174bc)),
      'OneDrive'=>(Icons.cloud_rounded,const Color(0xff0078d4)),
      'GoogleDrive'=>(Icons.change_history_rounded,const Color(0xff4285f4)),
      'MYBOX'=>(Icons.cloud_outlined,const Color(0xff219765)),
      'TeraBox'=>(Icons.cloud_queue_rounded,const Color(0xff4785c9)),
      _=>(Icons.storage_rounded,Theme.of(context).colorScheme.primary),
    };
    return Container(width:32,height:32,decoration:BoxDecoration(
      color:color.withValues(alpha:.13),borderRadius:BorderRadius.circular(8)),
      child:Center(child:CloudLogo(protocol,size:21,fallback:Icon(glyph,color:color,size:21))));

  }
}

class RemoteEditorOverlay extends StatefulWidget {
  const RemoteEditorOverlay({super.key,required this.data,required this.labels,required this.host,required this.onClose});
  final Map<String,Object?> data;
  final Map<String,String> labels;
  final RemoteEditorHost host;
  final VoidCallback onClose;
  @override
  State<RemoteEditorOverlay> createState()=>_RemoteEditorOverlayState();
}
class _RemoteEditorOverlayState extends State<RemoteEditorOverlay>{
  String? chosen;
  @override
  void initState(){super.initState();if(widget.data['editing']==true)chosen=widget.data['protocol'] as String;}
  String t(String key)=>widget.labels[key]??key;
  @override
  Widget build(BuildContext context){
    final type=chosen;
    if(type!=null){return RemoteEditorScreen(key:ValueKey(type),compact:true,
      data:widget.data['editing']==true?widget.data:{...widget.data,'protocol':type,'port':remotePorts[remoteProtocols.indexOf(type)]},
      labels:widget.labels,host:widget.host,onCancel:widget.onClose,onSaved:widget.onClose);}
    return AlertDialog(title:Text(t('editorAdd'),style:const TextStyle(fontSize:18,fontWeight:FontWeight.w600)),insetPadding:const EdgeInsets.symmetric(horizontal:20,vertical:16),constraints:const BoxConstraints(maxWidth:420),
      contentPadding:const EdgeInsets.fromLTRB(16,6,16,4),
      content:SizedBox(width:360,child:SingleChildScrollView(child:Column(mainAxisSize:MainAxisSize.min,children:[
        for(final group in [['SMB','FTP','SFTP','WebDAV'],['GoogleDrive','OneDrive','MYBOX','TeraBox']])...[
          Align(alignment:Alignment.centerLeft,child:Padding(padding:const EdgeInsets.only(left:8,top:6,bottom:2),
            child:Text(t(group.first=='SMB'?'networkHeading':'cloudHeading'),style:Theme.of(context).textTheme.labelMedium))),
          for(final protocol in group)ListTile(key:ValueKey('choose-$protocol'),dense:true,
            minTileHeight:44,visualDensity:VisualDensity.compact,contentPadding:const EdgeInsets.symmetric(horizontal:8),leading:ProtocolIcon(protocol),title:Text(protocol=='GoogleDrive'?'Google Drive':protocol,style:const TextStyle(fontSize:14)),onTap:()=>setState(()=>chosen=protocol)),
          if(group.first=='SMB')const Divider(),
        ],
      ]))),actions:[TextButton(onPressed:widget.onClose,child:Text(t('cancel')))]);
  }
}
