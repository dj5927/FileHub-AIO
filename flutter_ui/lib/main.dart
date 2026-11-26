import 'compact_text.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';
import 'navigation.dart';
import 'remote_editor.dart';
import 'explorer.dart';
import 'file_operations.dart';
import 'viewer.dart';
import 'native_panel.dart';

/// Stable UI-only bridge. Network passwords, tokens, file paths and bytes
/// stay in the native engine. The host re-checks entitlement on every action.
abstract interface class FileHubHost {
  Future<Map<Object?, Object?>> snapshot();
  Future<Map<Object?, Object?>> setLanguage(int index);
  Future<Map<Object?, Object?>> setTheme(int index);
  Future<void> action(String name, [String? id]);
  Future<void> reorderFavorites(List<String> ids);
}

final class AndroidHost implements FileHubHost, RemoteEditorHost, ExplorerHost, FilePromptHost, ViewerHost, NativePanelHost {
  static const channel = MethodChannel('com.filehubaio/ui.v1');
  int? epoch;

  Future<Map<Object?, Object?>> _map(String method, [Object? arg]) async {
    final result = await channel.invokeMapMethod<Object?, Object?>(method, arg);
    if (result == null) throw StateError('Missing host snapshot');
    return result;
  }

  @override
  Future<Map<Object?, Object?>> snapshot() => _map('snapshot');
  @override
  Future<Map<Object?, Object?>> setLanguage(int index) =>
      _map('setLanguage', index);
  @override
  Future<Map<Object?, Object?>> setTheme(int index) => _map('setTheme', index);
  @override
  Future<void> action(String name, [String? id]) =>
      channel.invokeMethod<void>('action', {'name': name, 'id': id,'epoch':epoch});
  @override
  Future<void> reorderFavorites(List<String> ids) =>
      channel.invokeMethod<void>('reorderFavorites', ids);
  Future<void> drawerAction(String name,[String? id])=>
      channel.invokeMethod<void>('drawerAction',{'epoch':epoch,'name':name,'id':id});
  Future<void> drawerReorder(List<String> ids)=>
      channel.invokeMethod<void>('drawerReorder',{'epoch':epoch,'order':ids});
  @override
  Future<void> saveRemote(Map<String,Object?> draft) =>
      channel.invokeMethod<void>('editorSave',draft);
  @override
  Future<bool> loginTeraBox() async =>
      await channel.invokeMethod<bool>('editorLogin') ?? false;
  @override
  Future<void> cloudHelp(String protocol) =>
      channel.invokeMethod<void>('editorHelp',protocol);
  @override
  Future<void> classicEditor() =>
      channel.invokeMethod<void>('editorClassic');
  @override
  Future<Map<Object?,Object?>> explorerPage(String revision,int offset) =>
      _map('explorerPage',{'revision':revision,'offset':offset});
  @override
  Future<Map<Object?,Object?>> explorerPreview(String id) =>
      _map('explorerPreview',id);
  @override
  Future<void> submitFilePrompt(String nonce,String value) =>
      channel.invokeMethod<void>('promptSubmit',{'nonce':nonce,'value':value});
  @override
  Future<void> viewerCommand(String nonce,String command,[int? value]) =>
      channel.invokeMethod<void>('viewerCommand',{'nonce':nonce,'command':command,'value':value});
  @override
  Future<void> panelCommand(String revision,String id,[int? value]) =>
      channel.invokeMethod<void>('panelCommand',{'revision':revision,'id':id,'value':value});
}

final class EmbeddedEditorHost implements RemoteEditorHost {
  const EmbeddedEditorHost(this.token);
  final String token;
  Future<T?> command<T>(String method,[Object? args])=>AndroidHost.channel.invokeMethod<T>(
    'editorCommand',{'token':token,'method':method,'args':args});
  @override Future<void> saveRemote(Map<String,Object?> draft) async {await command<void>('editorSave',draft);}
  @override Future<bool> loginTeraBox() async =>await command<bool>('editorLogin')??false;
  @override Future<void> cloudHelp(String protocol) async {await command<void>('editorHelp',protocol);}
  @override Future<void> classicEditor() async {}
}

final class ConnectionSummary {
  const ConnectionSummary(this.id, this.label, this.protocol, this.allowed,
      this.readOnly);
  factory ConnectionSummary.fromMap(Map<Object?, Object?> row) =>
      ConnectionSummary(row['id'] as String, row['label'] as String,
          row['protocol'] as String, row['allowed'] == true,
          row['readOnly'] == true);
  final String id, label, protocol;
  final bool allowed, readOnly;
}

final class HubSnapshot {
  HubSnapshot(Map<Object?, Object?> data)
      : language = data['language'] as String,
        languageIndex = data['languageIndex'] as int,
        themeIndex = data['themeIndex'] as int,
        dark = data['dark'] == true,
        direct = data['direct'] == true,
        full = data['full'] == true,
        version = data['version'] as String,
        route = data['route'] as String,
        surfaceEpoch = data['surfaceEpoch'] as int?,
        labels = Map<String, String>.from(data['labels'] as Map),
        languages = List<String>.from(data['languages'] as List),
        themes = List<String>.from(data['themes'] as List),
        profiles = (data['profiles'] as List)
            .map((e) => ConnectionSummary.fromMap(Map<Object?, Object?>.from(e as Map)))
            .toList(growable: false),
        profileError = data['profileError'] == true,
        favoriteError = data['favoriteError'] == true,
        hasSd = data['hasSd'] == true,
        favoritesExpanded = data['favoritesExpanded'] != false,
        networkExpanded = data['networkExpanded'] != false,
        freeBytes = (data['freeBytes'] as int?) ?? -1,
        favorites = ((data['favorites'] as List?) ?? [])
            .map((e) => FavoriteSummary.fromMap(Map<Object?, Object?>.from(e as Map)))
            .toList(growable: false),
        editor = data['editor'] is Map
            ? Map<String,Object?>.from(data['editor'] as Map)
            : const {},
        explorer = data['explorer'] is Map
            ? Map<Object?,Object?>.from(data['explorer'] as Map)
            : const {},
        transfer = data['transfer'] is Map
            ? Map<Object?,Object?>.from(data['transfer'] as Map) : const {},
        prompt = data['prompt'] is Map
            ? Map<Object?,Object?>.from(data['prompt'] as Map) : const {},
        viewer = data['viewer'] is Map
            ? Map<Object?,Object?>.from(data['viewer'] as Map) : const {},
        panel = data['panel'] is Map
            ? Map<Object?,Object?>.from(data['panel'] as Map) : const {};

  final String language, version, route;
  final int? surfaceEpoch;
  final int languageIndex, themeIndex;
  final bool dark, direct, full, profileError;
  final bool favoriteError, hasSd, favoritesExpanded, networkExpanded;
  final int freeBytes;
  final Map<String, String> labels;
  final List<String> languages, themes;
  final List<ConnectionSummary> profiles;
  final List<FavoriteSummary> favorites;
  final Map<String,Object?> editor;
  final Map<Object?,Object?> explorer;
  final Map<Object?,Object?> transfer,prompt,viewer,panel;
  String t(String key) => labels[key] ?? key;
}

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  // Never rasterize the empty bootstrap Scaffold before native data arrives.
  WidgetsBinding.instance.deferFirstFrame();
  runApp(FileHubUi(host: AndroidHost(),deferInitialFrame:true));
}

class FileHubUi extends StatefulWidget {
  const FileHubUi({super.key, required this.host,this.deferInitialFrame=false});
  final FileHubHost host;
  final bool deferInitialFrame;
  @override
  State<FileHubUi> createState() => _FileHubUiState();
}

class _FileHubUiState extends State<FileHubUi> with WidgetsBindingObserver {
  HubSnapshot? model;
  HubSnapshot? drawerModel;
  Map<Object?,Object?>? editorOverlay;
  bool editorClosing=false;
  bool drawerBusy=false;
  bool busy = true;
  bool failed = false;
  bool refreshQueued=false;
  Map<Object?,Object?>? liveTransfer;
  Map<Object?,Object?>? liveViewer;
  int generation = 0;
  bool initialFrameReleased=false;
  void releaseInitialFrame(){
    if(widget.deferInitialFrame&&!initialFrameReleased){
      initialFrameReleased=true;
      WidgetsBinding.instance.allowFirstFrame();
    }
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    if (widget.host is AndroidHost) {
      AndroidHost.channel.setMethodCallHandler((call) async {
        if (!mounted) return;
        if(call.method=='transferUpdate'&&call.arguments is Map){
          setState(()=>liveTransfer=Map<Object?,Object?>.from(call.arguments as Map));
        }else if(call.method=='viewerUpdate'&&call.arguments is Map){
          final update=Map<Object?,Object?>.from(call.arguments as Map);
          if(model?.route=='viewer'&&update['nonce']==model?.viewer['nonce']){
            setState(()=>liveViewer=update);
          }
        }else if(call.method=='editorOpen'&&call.arguments is Map&&model?.route=='connections'){
          setState(()=>editorOverlay=Map<Object?,Object?>.from(call.arguments as Map));
        }else if(call.method=='editorDismiss'){
          if(editorOverlay?['token']==call.arguments)setState(()=>editorOverlay=null);
        }else if(call.method=='editorBack'){
          if(editorOverlay?['token']==call.arguments)await closeEditor();
        }else if(call.method=='drawerOpen'&&call.arguments is Map){
          final next=HubSnapshot(Map<Object?,Object?>.from(call.arguments as Map));
          if(next.surfaceEpoch==model?.surfaceEpoch){
            setState(()=>drawerModel=next);
          }
        }else if(call.method=='drawerClose'){
          setState(()=>drawerModel=null);
        }else if(call.method=='routeChanged'){
          // Supersede any in-flight snapshot for the previous directory.
          setState((){drawerModel=null;editorOverlay=null;});
          refreshQueued=false;
          await reload();
        }else if(call.method=='refresh'){
          if(busy){refreshQueued=true;}else{await reload();}
        }
      });
    }
    reload();
  }

  @override
  void dispose() {
    releaseInitialFrame();
    WidgetsBinding.instance.removeObserver(this);
    if (widget.host is AndroidHost) AndroidHost.channel.setMethodCallHandler(null);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && !busy && model?.route != 'editor' && editorOverlay==null) reload();
  }

  @override
  void didChangePlatformBrightness() {
    if (!busy && model?.route != 'editor' && editorOverlay==null) reload();
  }

  Future<void> reload() => _change(widget.host.snapshot);

  Future<void> drainRefresh() async {
    if(mounted&&!busy&&refreshQueued){
      refreshQueued=false;
      await reload();
    }
  }

  Future<void> _change(Future<Map<Object?, Object?>> Function() operation) async {
    final token = ++generation;
    if (mounted) setState(() { busy = true; failed = false; });
    try {
      final pending=operation();
      final updated = HubSnapshot(await (model==null?
          pending.timeout(const Duration(seconds:8)):pending));
      if (!mounted || token != generation) return;
      setState(() {
        model = updated; busy = false;
        if(widget.host is AndroidHost)(widget.host as AndroidHost).epoch=updated.surfaceEpoch;
        if(updated.route=='viewer')liveViewer=null;
      });
      if (widget.host is AndroidHost && (updated.route == 'home' || updated.route == 'drawer' ||
          updated.route == 'explorer'||updated.route=='shelf'||updated.route=='transfer'||updated.route=='filePrompt'||updated.route=='viewer'||updated.route=='panel'||updated.route=='connections'||updated.route=='favorites')) {
        WidgetsBinding.instance.addPostFrameCallback((_) async {
          if (!mounted || model != updated) return;
          try {
            await AndroidHost.channel.invokeMethod<void>('uiReady',updated.surfaceEpoch);
            // A real subsequent texture buffer, not merely a Dart layout
            // callback, releases the Android presentation gate.
            if(mounted&&model==updated)WidgetsBinding.instance.scheduleFrame();
          }
          catch (_) { /* Native fallback remains available. */ }
        });
      }
    } catch (_) {
      if (!mounted || token != generation) return;
      setState(() { busy = false; failed = true; });
    } finally {
      if(mounted&&token==generation)releaseInitialFrame();
    }
    await drainRefresh();
  }

  Future<void> _action(String name, [String? id]) async {
    if (busy) return;
    setState(() { busy = true; failed = false; });
    try {
      await widget.host.action(name, id);
      if (!mounted) return;
      if (name == 'toggleFavorites' || name == 'toggleNetwork' || name == 'entrySelect' ||
          const ['selectAll','clearSelection','copySelected','moveSelected','clearClipboard'].contains(name)) {
        await reload();
      } else {
        setState(() => busy = false);
      }
    } catch (_) {
      if (mounted) setState(() { busy = false; failed = true; });
    }
    await drainRefresh();
  }

  Future<void> _reorder(List<String> ids) async {
    if (busy) return;
    setState(() { busy = true; failed = false; });
    try {
      await widget.host.reorderFavorites(ids);
      if (mounted) await reload();
    } catch (_) {
      if (mounted) setState(() { busy = false; failed = true; });
    }
  }

  Future<void> closeEditor()async{
    final token=editorOverlay?['token'];
    if(token is! String||editorClosing)return;
    editorClosing=true;
    try{
      await EmbeddedEditorHost(token).command<void>('editorCancel');
      if(mounted&&editorOverlay?['token']==token){
        FocusManager.instance.primaryFocus?.unfocus();
        setState(()=>editorOverlay=null);
      }
    }catch(_){/* A busy editor remains open. */}
    finally{editorClosing=false;}
  }
  Future<void> _drawerAction(String action,[String? id])async{
    if(drawerBusy||drawerModel==null||widget.host is! AndroidHost)return;
    setState(()=>drawerBusy=true);
    try{await (widget.host as AndroidHost).drawerAction(action,id);}
    catch(_){/* A stale action must not dismiss a newer drawer. */}
    finally{if(mounted)setState(()=>drawerBusy=false);}
  }
  Future<void> _drawerReorder(List<String> ids)async{
    if(drawerBusy||drawerModel==null||widget.host is! AndroidHost)return;
    setState(()=>drawerBusy=true);
    try{await (widget.host as AndroidHost).drawerReorder(ids);}
    catch(_){/* Keep the authoritative order supplied by the host. */}
    finally{if(mounted)setState(()=>drawerBusy=false);}
  }
  @override
  Widget build(BuildContext context) {
    final data = model;
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'FileHub AIO',
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      supportedLocales: const [Locale('en'), Locale('ko'), Locale('ja'), Locale('zh', 'CN')],
      locale: data == null ? null :
          (data.language == 'zh' ? const Locale('zh', 'CN') : Locale(data.language)),
      theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xff0b8f83)),
          useMaterial3: true, scaffoldBackgroundColor: const Color(0xfff8f9fb)),
      darkTheme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xff4cc8b8),
          brightness: Brightness.dark), useMaterial3: true,scaffoldBackgroundColor:const Color(0xff14181d)),
      themeMode: data==null?ThemeMode.system:data.dark?ThemeMode.dark:ThemeMode.light,
      home: Builder(builder: (context) {
        Widget content(){
        if (data == null) {
          final lang = Localizations.localeOf(context).languageCode;
          final retry = switch (lang) {'ko' => '다시 시도', 'ja' => '再試行', 'zh' => '重试', _ => 'Retry'};
          return Scaffold(
            appBar: AppBar(title: const Text('FileHub AIO'), leading: BackButton(onPressed: SystemNavigator.pop)),
            body: Center(child: failed
                ? FilledButton.icon(onPressed: reload, icon: const Icon(Icons.refresh), label: Text(retry))
                : const CircularProgressIndicator()),
          );
        }
        if (data.route == 'editor' && widget.host is RemoteEditorHost) {
          return RemoteEditorScreen(data:data.editor,labels:data.labels,
              host:widget.host as RemoteEditorHost, onCancel:SystemNavigator.pop);
        }
        if(data.route=='viewer'&&widget.host is ViewerHost){
          return ViewerPanel(key:ValueKey(data.viewer['nonce']),data:liveViewer??data.viewer,
            labels:data.labels,host:widget.host as ViewerHost);
        }
        if((data.route=='panel'||data.route=='shelf')&&widget.host is NativePanelHost){
          return NativePanel(data:data.panel,labels:data.labels,
            host:widget.host as NativePanelHost,busy:busy,reload:reload);
        }
        if(data.route=='transfer'){
          return Material(color:Theme.of(context).colorScheme.surface,
            child:TransferPanel(data:liveTransfer??data.transfer,labels:data.labels,busy:busy,action:_action));
        }
        if(data.route=='filePrompt'&&widget.host is FilePromptHost){
          return Material(color:Theme.of(context).colorScheme.surface,
            child:FilePromptPanel(key:ValueKey(data.prompt['nonce']),data:data.prompt,labels:data.labels,
              host:widget.host as FilePromptHost,action:_action));
        }
        if (data.route == 'explorer' && widget.host is ExplorerHost) {
          return Material(color:Theme.of(context).scaffoldBackgroundColor,
            child:Column(children:[
              if(failed)MaterialBanner(content:Text(data.t('error')),
                actions:[TextButton(onPressed:reload,child:Text(data.t('refresh')))]),
              Expanded(child:ExplorerScreen(
                key:ValueKey(data.explorer['revision']),
                data:data.explorer,labels:data.labels,busy:busy,
                host:widget.host as ExplorerHost,action:_action)),
            ]));
        }
        if (data.route == 'home' || data.route == 'drawer') {
          return Material(
            color: Theme.of(context).scaffoldBackgroundColor,
            child: Column(children: [
              if (busy) const LinearProgressIndicator(),
              if (failed) MaterialBanner(content: Text(data.t('error')),
                  actions: [TextButton(onPressed: reload, child: Text(data.t('refresh')))]),
              Expanded(child: data.route == 'home'
                  ? HomeNavigation(data: data, busy: busy, action: _action)
                  : DrawerNavigation(data: data, busy: busy, action: _action, reorder: _reorder)),
            ]),
          );
        }
        if(data.surfaceEpoch!=null&&(data.route=='connections'||data.route=='favorites')){
          final children=data.route=='connections'?_connections(data):_favorites(data);
          return Material(color:Theme.of(context).scaffoldBackgroundColor,
            child:Column(children:[
              if(busy)const LinearProgressIndicator(minHeight:2),
              if(failed)MaterialBanner(content:Text(data.t('error')),
                actions:[TextButton(onPressed:reload,child:Text(data.t('refresh')))]),
              Expanded(child:Align(alignment:Alignment.topCenter,child:ConstrainedBox(
                constraints:const BoxConstraints(maxWidth:840),
                child:ListView(key:ValueKey(data.route),padding:data.route=='favorites'
                    ?const EdgeInsets.fromLTRB(10,8,10,85):const EdgeInsets.fromLTRB(16,12,16,85),
                  children:children),
              ))),
            ]));
        }
        return Scaffold(
          appBar: AppBar(
            title: Text(data.t(data.route == 'interface' ? 'interface' : 'connections')),
            leading: BackButton(onPressed: SystemNavigator.pop),
            actions: data.route == 'connections' ? [
              IconButton(onPressed: busy ? null : () => _action('add'),
                  tooltip: data.t('add'), icon: const Icon(Icons.add)),
              IconButton(onPressed: busy ? null : reload,
                  tooltip: data.t('refresh'), icon: const Icon(Icons.refresh)),
            ] : null,
          ),
          body: SafeArea(top: false, child: Column(children: [
            if (busy) const LinearProgressIndicator(),
            if (failed) MaterialBanner(content: Text(data.t('error')),
                actions: [TextButton(onPressed: reload, child: Text(data.t('refresh')))]),
            Expanded(child: Align(alignment: Alignment.topCenter, child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 840),
              child: ListView(padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
                children: data.route == 'interface' ? _interface(data) : _connections(data)),
            ))),
          ])),
        );
        }
        final drawer=drawerModel;
        final editor=editorOverlay;
        return Stack(children:[
          Positioned.fill(child:IgnorePointer(ignoring:drawer!=null||editor!=null,child:content())),
          Positioned.fill(child:IgnorePointer(ignoring:drawer==null,
            child:AnimatedOpacity(opacity:drawer==null?0:1,duration:const Duration(milliseconds:220),
              child:GestureDetector(key:const ValueKey('drawer-dismiss'),onTap:()=>_drawerAction('close'),behavior:HitTestBehavior.opaque,
                child:const ColoredBox(color:Color(0x88000000)))))),
          Positioned(left:0,top:0,bottom:0,child:IgnorePointer(ignoring:drawer==null,
            child:SizedBox(width:(MediaQuery.sizeOf(context).width*.92).clamp(0.0,360.0),
              child:AnimatedSwitcher(duration:const Duration(milliseconds:260),
                reverseDuration:const Duration(milliseconds:220),
                switchInCurve:Curves.easeOutCubic,switchOutCurve:Curves.easeInCubic,
                layoutBuilder:(current,previous)=>Stack(alignment:Alignment.topLeft,
                  children:[...previous,?current]),
                transitionBuilder:(child,animation)=>SlideTransition(
                  position:Tween<Offset>(begin:const Offset(-1,0),end:Offset.zero).animate(animation),
                  child:child),
                child:drawer==null?const SizedBox.shrink(key:ValueKey('drawer-hidden')):
                  SizedBox.expand(key:const ValueKey('drawer-visible'),
                    child:Material(color:Theme.of(context).colorScheme.surface,elevation:8,
                      child:DrawerNavigation(data:drawer,busy:drawerBusy,
                        action:_drawerAction,reorder:_drawerReorder))))))),
          if(editor!=null)...[
            const Positioned.fill(child:ModalBarrier(dismissible:false,color:Color(0x8a000000))),
            Positioned.fill(child:RemoteEditorOverlay(key:ValueKey(editor['token']),
              data:Map<String,Object?>.from(editor['editor'] as Map),
              labels:Map<String,String>.from(editor['labels'] as Map),
              host:EmbeddedEditorHost(editor['token'] as String),onClose:closeEditor)),
          ],
        ]);

      }),
    );
  }

  Widget _heading(String title) => Padding(
      padding: const EdgeInsets.fromLTRB(4, 16, 4, 8),
      child: Text(title, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)));

  List<Widget> _interface(HubSnapshot d) => [
    _heading(d.t('language')),
    Card(child: Column(children: [
      for (var i = 0; i < d.languages.length; i++)
        ListTile(key: ValueKey('language-$i'), title: CompactText(d.languages[i]),
          leading: Icon(i == d.languageIndex ? Icons.radio_button_checked : Icons.radio_button_unchecked),
          selected: i == d.languageIndex, enabled: !busy,
          onTap: () => _change(() => widget.host.setLanguage(i))),
    ])),
    Padding(padding: const EdgeInsets.all(8), child: Text(d.t('languageNote'))),
    _heading(d.t('theme')),
    Card(child: Column(children: [
      for (var i = 0; i < d.themes.length; i++)
        ListTile(key: ValueKey('theme-$i'), title: CompactText(d.themes[i]),
          leading: Icon([Icons.brightness_auto, Icons.light_mode, Icons.dark_mode][i]),
          trailing: i == d.themeIndex ? const Icon(Icons.check_circle) : null,
          selected: i == d.themeIndex, enabled: !busy,
          onTap: () => _change(() => widget.host.setTheme(i))),
    ])),
    Padding(padding: const EdgeInsets.all(8), child: Text(d.t('themeNote'))),
    const SizedBox(height: 16),
    ListTile(leading: const Icon(Icons.info_outline),
      title: Text('FileHub AIO v${d.version}'), subtitle: Text(d.direct ? 'Direct' : 'Play')),
  ];

  List<Widget> _connections(HubSnapshot d) => [
    if (!d.full)
      Card(child: ListTile(leading: const Icon(Icons.workspace_premium),
          title: CompactText(d.t('upgrade')), subtitle: CompactText(d.t('limit')),
          trailing: const Icon(Icons.chevron_right), enabled: !busy,
          onTap: () => _action('upgrade'))),
    _heading(d.t('connections')),
    if (d.profileError)
      Padding(padding: const EdgeInsets.all(12), child: Text(d.t('profileError')))
    else if (d.profiles.isEmpty)
      Padding(padding: const EdgeInsets.all(12), child: Text(d.t('empty'))),
    for (final p in d.profiles)
      Card(child: ListTile(
        key: ValueKey('profile-${p.id}'),
        leading: Icon(p.allowed ? Icons.dns_outlined : Icons.lock_outline),
        title: Text(p.label, maxLines: 2, overflow: TextOverflow.ellipsis),
        subtitle: Text([p.protocol, if (p.readOnly) d.t('readOnly'),
          if (!p.allowed) d.t('locked')].join(' · ')),
        enabled: !busy,
        onTap: () => _action(p.allowed ? 'open' : 'upgrade', p.id),
        trailing: Row(mainAxisSize:MainAxisSize.min,children:[
          if(p.allowed) IconButton(icon:const Icon(Icons.edit_outlined),
            tooltip:d.t('manage'),onPressed:busy?null:()=>_action('edit',p.id)),
          IconButton(icon: const Icon(Icons.more_vert), tooltip: d.t('manage'),
            onPressed: busy ? null : () => _action('manage', p.id)),
        ]),
      )),
    const SizedBox(height: 12),
    FilledButton.icon(onPressed: busy ? null : () => _action('add'),
        icon: const Icon(Icons.add), label: Text(d.t('add'))),
    const SizedBox(height: 8),
    OutlinedButton.icon(onPressed: busy ? null : () => _action('backup'),
        icon: const Icon(Icons.import_export), label: Text(d.t('backup'))),
  ];
  List<Widget> _favorites(HubSnapshot d)=>[
    _heading(d.t('favorites')),
    if(d.favoriteError)
      Padding(padding:const EdgeInsets.all(12),child:Text(d.t('favoriteError')))
    else if(d.favorites.isEmpty)
      Padding(padding:const EdgeInsets.symmetric(vertical:32),child:Column(children:[
        const Icon(Icons.star_border_rounded,size:44),
        const SizedBox(height:12),Text(d.t('favoriteEmpty')),
      ])),
    for(var i=0;i<d.favorites.length;i++)...[
      if(i>0)const FileRowDivider(),
      _favoriteListRow(d,d.favorites[i]),
    ],
  ];
  Widget _favoriteListRow(HubSnapshot d,FavoriteSummary f) => Builder(builder:(context){
    final colors=Theme.of(context).colorScheme;
    return Material(color:colors.surface,child:InkWell(
      key:ValueKey('favorite-list-${f.id}'),
      onTap:busy?null:()=>_action('favorite',f.id),
      onLongPress:busy?null:()=>_action('favoriteManage',f.id),
      child:ConstrainedBox(constraints:const BoxConstraints(minHeight:54),
        child:Padding(padding:const EdgeInsets.symmetric(horizontal:8),
          child:Row(children:[
            const FileTypeIcon(kind:'favorite',size:38),
            const SizedBox(width:12),
            Expanded(child:Column(mainAxisAlignment:MainAxisAlignment.center,
              crossAxisAlignment:CrossAxisAlignment.start,children:[
                Text(f.label,maxLines:1,overflow:TextOverflow.ellipsis,
                  style:const TextStyle(fontWeight:FontWeight.w600,fontSize:14)),
                if(f.original.isNotEmpty)
                  Text(f.original,maxLines:1,overflow:TextOverflow.ellipsis,
                    style:Theme.of(context).textTheme.bodySmall),
              ])),
            IconButton(key:ValueKey('favorite-manage-${f.id}'),
              icon:const Icon(Icons.more_vert,size:20),tooltip:d.t('manage'),
              onPressed:busy?null:()=>_action('favoriteManage',f.id)),
          ])))));
  });

}
