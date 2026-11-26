import 'package:filehub_flutter/explorer.dart';
import 'package:filehub_flutter/main.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

final class FakeHost implements FileHubHost {
  FakeHost(this.route, {this.full = false, this.direct = false});
  final String route;
  final bool full, direct;
  int language = 1, theme = 0;
  String? lastAction, lastId;
  bool fail = false;
  bool embedded = false;
  bool sd = true, favoritesExpanded = true, networkExpanded = true;
  List<String> favoriteIds = ['f1','f2'];
  @override
  Future<Map<Object?, Object?>> snapshot() async {
    if (fail) throw StateError('test failure');
    return {
      'language': ['en', 'ko', 'en', 'ja', 'zh'][language],
      'languageIndex': language,
      'themeIndex': theme,
      'dark': theme == 2,
      'direct': direct,
      'full': full,
      'version': direct ? '0.1.123' : '1.0.8',
      'route': route,
      if(embedded)'surfaceEpoch':7,
      'hasSd': sd,
      'freeBytes': 5368709120,
      'favoritesExpanded': favoritesExpanded,
      'networkExpanded': networkExpanded,
      'favorites': favoriteIds.map((id)=>{'id':id,'label':id,'original':''}).toList(),
      'labels': {
        'interface': 'Interface', 'connections': 'Connections',
        'language': 'Language', 'theme': 'Theme',
        'languageNote': 'System language', 'themeNote': 'System theme',
        'upgrade': 'Upgrade', 'limit': 'One per protocol',
        'locked': 'Full version required', 'manage': 'Manage',
        'readOnly': 'Read only', 'empty': 'No connections',
        'refresh': 'Refresh', 'add': 'Add', 'backup': 'Backup',
        'nativeList': 'Native list', 'error': 'Error',
        'profileError': 'Unable to read connections',
        'storageHeading':'Storage','storage':'Main storage','sd':'SD card',
        'downloads':'Downloads','images':'Images','audio':'Music','videos':'Videos',
        'features':'Extras','videoShelf':'Video shelf','comicShelf':'Comics','textShelf':'Books',
        'network':'Network','networkManage':'Manage connections','favorites':'Favorites',
        'premiumUpgrade':'Premium upgrade','favoriteEmpty':'No favorites','favoriteError':'Favorite error','settings':'Settings',
        'home':'Home','nativeHome':'Classic home','nativeDrawer':'Classic menu',
        'available':'Free space','close':'Close','reorder':'Reorder',
      },
      'languages': ['System', 'Korean', 'English', 'Japanese', 'Chinese'],
      'themes': ['System', 'Light', 'Dark'],
      'profiles': [
        {'id': 'a', 'label': 'NAS A', 'protocol': 'SMB',
         'allowed': true, 'readOnly': false},
        {'id': 'b', 'label': 'NAS B', 'protocol': 'SMB',
         'allowed': full, 'readOnly': true},
      ],
      'profileError': false,
    };
  }
  @override
  Future<Map<Object?, Object?>> setLanguage(int index) async {
    language = index;
    return snapshot();
  }
  @override
  Future<Map<Object?, Object?>> setTheme(int index) async {
    theme = index;
    return snapshot();
  }
  @override
  Future<void> action(String name, [String? id]) async {
    lastAction = name;
    lastId = id;
    if(name=='toggleFavorites') favoritesExpanded=!favoritesExpanded;
    if(name=='toggleNetwork') networkExpanded=!networkExpanded;
  }
  @override
  Future<void> reorderFavorites(List<String> ids) async {
    favoriteIds=List.of(ids);
  }
}

void main() {
  testWidgets('embedded connection and favorites lists do not create an extra app bar',(tester)async{
    final remote=FakeHost('connections',full:true)..embedded=true;
    await tester.pumpWidget(FileHubUi(key:const ValueKey('remote'),host:remote));
    await tester.pumpAndSettle();
    expect(find.byType(AppBar),findsNothing);
    expect(find.text('Native list'),findsNothing);
    await tester.tap(find.byKey(const ValueKey('profile-a')));
    await tester.pumpAndSettle();
    expect(remote.lastAction,'open');
    final favorites=FakeHost('favorites',full:true)..embedded=true;
    await tester.pumpWidget(FileHubUi(key:const ValueKey('favorites'),host:favorites));
    await tester.pumpAndSettle();
    expect(find.byType(AppBar),findsNothing);
    expect(find.byType(FileRowDivider),findsOneWidget);
    expect(find.byType(Card),findsNothing);
    await tester.tap(find.byKey(const ValueKey('favorite-manage-f1')));
    await tester.pumpAndSettle();
    expect(favorites.lastAction,'favoriteManage');
    expect(favorites.lastId,'f1');
    await tester.tap(find.byKey(const ValueKey('favorite-list-f1')));
    await tester.pumpAndSettle();
    expect(favorites.lastAction,'favorite');
    await tester.longPress(find.byKey(const ValueKey('favorite-list-f2')));
    await tester.pumpAndSettle();
    expect(favorites.lastAction,'favoriteManage');
    expect(favorites.lastId,'f2');
  });
  testWidgets('drawer overlay keeps the underlying home on the same host',(tester)async{
    tester.view.physicalSize=const Size(360,760);
    tester.view.devicePixelRatio=1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final home=FakeHost('home',full:true)..embedded=true;
    final drawer=FakeHost('drawer',full:true)..embedded=true;
    final snapshot=await home.snapshot();
    final drawerSnapshot=await drawer.snapshot();
    final messenger=TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    final actions=<String>[];
    final orders=<List<String>>[];
    messenger.setMockMethodCallHandler(AndroidHost.channel,(call)async{
      if(call.method=='snapshot')return snapshot;
      if(call.method=='drawerAction'){
        actions.add(call.arguments['name'] as String);
      }
      if(call.method=='drawerReorder'){
        expect(call.arguments['epoch'],7);
        final ids=List<String>.from(call.arguments['order']);
        orders.add(ids);drawer.favoriteIds=ids;
      }
      return null;
    });
    addTearDown(()=>messenger.setMockMethodCallHandler(AndroidHost.channel,null));
    await tester.pumpWidget(FileHubUi(host:AndroidHost()));
    await tester.pumpAndSettle();
    Future<void> send(String method,Object? args)async{
      await messenger.handlePlatformMessage(AndroidHost.channel.name,
        const StandardMethodCodec().encodeMethodCall(MethodCall(method,args)),(_){});
      await tester.pumpAndSettle();
    }
    await send('drawerOpen',drawerSnapshot);
    expect(find.byKey(const ValueKey('drawer-native')),findsNothing);
    expect(find.byKey(const ValueKey('drawer-close')),findsNothing);
    expect(find.byKey(const ValueKey('drawer-scroll')),findsOneWidget);
    expect(tester.getSize(find.byKey(const ValueKey('drawer-panel'))).width,closeTo(360*.92*.8,0.1));
    expect(find.byKey(const ValueKey('home-storage')),findsOneWidget);
    final homeRow=find.byKey(const ValueKey('drawer-home'));
    expect(tester.getSize(homeRow).height,greaterThanOrEqualTo(50));
    final start=tester.getCenter(find.text('f1'));
    final end=tester.getCenter(find.text('f2'));
    final g=await tester.startGesture(start);
    await tester.pump(const Duration(milliseconds:650));
    for(var i=0;i<8;i++){
      await g.moveBy(Offset(0,(end.dy-start.dy+25)/8));
      await tester.pump(const Duration(milliseconds:40));
    }
    await g.up();await tester.pumpAndSettle();
    expect(orders,[['f2','f1']]);
    await send('drawerOpen',await drawer.snapshot());
    expect(tester.getTopLeft(find.text('f2')).dy,lessThan(tester.getTopLeft(find.text('f1')).dy));
    await tester.tapAt(Offset(tester.view.physicalSize.width/tester.view.devicePixelRatio-5,200));
    await tester.pumpAndSettle();
    expect(actions,['close']);
    await send('drawerClose',null);
    expect(find.byKey(const ValueKey('drawer-scroll')),findsNothing);
    expect(find.byKey(const ValueKey('home-storage')),findsOneWidget);
    await send('drawerOpen',await drawer.snapshot());
    expect(tester.getTopLeft(find.text('f2')).dy,lessThan(tester.getTopLeft(find.text('f1')).dy));
    await send('drawerClose',null);
    await send('drawerOpen',Map<Object?,Object?>.from(drawerSnapshot)..['surfaceEpoch']=6);
    expect(find.byKey(const ValueKey('drawer-close')),findsNothing);
  });
  testWidgets('Direct home opens storage, SD, media and assigned shelves', (tester) async {
    final host=FakeHost('home',full:true,direct:true);
    await tester.pumpWidget(FileHubUi(host:host));
    await tester.pumpAndSettle();
    expect(find.text('Free space 5.0 GB'),findsOneWidget);
    expect(find.byKey(const ValueKey('home-upgrade')),findsNothing);
    expect(find.byKey(const ValueKey('home-native')),findsNothing);
    for(final action in ['storage','sd','downloads','images','audio','videos','videoShelf','comicShelf','textShelf','connections']){
      final key=find.byKey(ValueKey('home-$action'));
      await tester.scrollUntilVisible(key,120);
      await tester.ensureVisible(key);
      await tester.pumpAndSettle();
      await tester.tap(key);
      await tester.pumpAndSettle();
      expect(host.lastAction,action);
    }
  });
  testWidgets('Play home hides absent SD and body upgrade banner', (tester) async {
    final host=FakeHost('home')..sd=false;
    await tester.pumpWidget(FileHubUi(host:host));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('home-sd')),findsNothing);
    expect(find.byKey(const ValueKey('home-upgrade')),findsNothing);
  });
  testWidgets('drawer persists section toggles, favorite selection and reorder', (tester) async {
    final host=FakeHost('drawer',full:true);
    await tester.pumpWidget(FileHubUi(host:host));
    await tester.pumpAndSettle();
    final section=find.byKey(const ValueKey('drawer-toggleFavorites'));
    await tester.tap(section);
    await tester.pumpAndSettle();
    expect(host.favoritesExpanded,isFalse);
    expect(find.byKey(const ValueKey('drawer-favorite-f1')),findsNothing);
    await tester.tap(section);
    await tester.pumpAndSettle();
    expect(host.favoritesExpanded,isTrue);
    await tester.tap(find.byKey(const ValueKey('drawer-favorite-f1')));
    await tester.pumpAndSettle();
    expect(host.lastAction,'favorite');
    expect(host.lastId,'f1');
    final first=find.text('f1');
    final second=find.text('f2');
    final distance=tester.getCenter(second).dy-tester.getCenter(first).dy;
    final gesture=await tester.startGesture(tester.getCenter(first));
    await tester.pump(const Duration(milliseconds:650));
    for(var step=0;step<8;step++){
      await gesture.moveBy(Offset(0,(distance+25)/8));
      await tester.pump(const Duration(milliseconds:40));
    }
    await tester.pump(const Duration(milliseconds:400));
    await gesture.up();
    await tester.pumpAndSettle();
    expect(host.favoriteIds,['f2','f1']);
    expect(tester.getTopLeft(find.text('f2')).dy,lessThan(tester.getTopLeft(find.text('f1')).dy));
    final handle=find.byKey(const ValueKey('drawer-drag-f1'));
    final travel=tester.getCenter(handle).dy-tester.getCenter(find.byKey(const ValueKey('drawer-drag-f2'))).dy;
    final drag=await tester.startGesture(tester.getCenter(handle));
    await tester.pump(const Duration(milliseconds:650));
    for(var step=0;step<8;step++){
      await drag.moveBy(Offset(0,-(travel+25)/8));
      await tester.pump(const Duration(milliseconds:40));
    }
    await tester.pump(const Duration(milliseconds:400));
    await drag.up();await tester.pumpAndSettle();
    expect(host.favoriteIds,['f1','f2']);
    final network=find.byKey(const ValueKey('drawer-toggleNetwork'));
    await tester.ensureVisible(network);
    await tester.tap(network);
    await tester.pumpAndSettle();
    expect(host.networkExpanded,isFalse);
    expect(find.byKey(const ValueKey('drawer-profile-a')),findsNothing);
    expect(find.byKey(const ValueKey('drawer-settings')),findsNothing);
  });
  testWidgets('both editions long press the handle, vibrate once and move without lifting',(tester)async{
    tester.view.physicalSize=const Size(360,760);
    tester.view.devicePixelRatio=1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final messenger=TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    final haptics=<Object?>[];
    messenger.setMockMethodCallHandler(SystemChannels.platform,(call)async{
      if(call.method=='HapticFeedback.vibrate')haptics.add(call.arguments);
      return null;
    });
    addTearDown(()=>messenger.setMockMethodCallHandler(SystemChannels.platform,null));
    for(final direct in [true,false]){
      final host=FakeHost('drawer',direct:direct,full:direct);
      await tester.pumpWidget(FileHubUi(key:ValueKey(direct),host:host));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('drawer-close')),findsNothing);
      expect(tester.getSize(find.byKey(const ValueKey('drawer-home'))).height,greaterThanOrEqualTo(50));
      expect(find.byKey(const ValueKey('drawer-upgrade')),direct?findsNothing:findsOneWidget);
      final handle=find.byKey(const ValueKey('drawer-drag-f1'));
      final start=tester.getCenter(handle);
      final end=tester.getCenter(find.byKey(const ValueKey('drawer-drag-f2')));
      final before=haptics.length;
      final gesture=await tester.startGesture(start);
      await tester.pump(const Duration(milliseconds:650));
      expect(haptics.length,before+1);
      expect(find.text('Reorder'),findsNothing);
      // Holding longer must not trigger a tooltip or require a second press.
      await tester.pump(const Duration(milliseconds:900));
      expect(haptics.length,before+1);
      expect(find.text('Reorder'),findsNothing);
      for(var step=0;step<8;step++){
        await gesture.moveBy(Offset(0,(end.dy-start.dy+25)/8));
        await tester.pump(const Duration(milliseconds:40));
      }
      await gesture.up();await tester.pumpAndSettle();
      expect(host.favoriteIds,['f2','f1']);
      expect(tester.getTopLeft(find.text('f2')).dy,lessThan(tester.getTopLeft(find.text('f1')).dy));
      expect(host.lastAction,isNull);
    }
  });
  testWidgets('premium footer is available only to free Play edition',(tester)async{
    for(final entry in [(false,false),(false,true),(true,true)]){
      final host=FakeHost('drawer',direct:entry.$1,full:entry.$2);
      await tester.pumpWidget(FileHubUi(key:ValueKey(entry),host:host));
      await tester.pumpAndSettle();
      final premium=find.byKey(const ValueKey('drawer-upgrade'));
      expect(find.byKey(const ValueKey('drawer-settings')),findsNothing);
      if(!entry.$1&&!entry.$2){
        expect(premium,findsOneWidget);
        await tester.tap(premium);await tester.pumpAndSettle();
        expect(host.lastAction,'upgrade');
      }else{expect(premium,findsNothing);}
    }
  });
  testWidgets('interface changes use host preferences and update selection',
      (tester) async {
    final host = FakeHost('interface');
    await tester.pumpWidget(FileHubUi(host: host));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('language-3')));
    await tester.pumpAndSettle();
    expect(host.language, 3);
    expect(tester.widget<ListTile>(find.byKey(const ValueKey('language-3'))).selected, isTrue);
    final dark = find.byKey(const ValueKey('theme-2'));
    await tester.scrollUntilVisible(dark, 160);
    await tester.ensureVisible(dark);
    await tester.pumpAndSettle();
    await tester.tap(dark);
    await tester.pumpAndSettle();
    expect(host.theme, 2);
    expect(tester.widget<MaterialApp>(find.byType(MaterialApp)).themeMode, ThemeMode.dark);
  });
  testWidgets('Play free sends upgrade for locked profile, not open',
      (tester) async {
    final host = FakeHost('connections');
    await tester.pumpWidget(FileHubUi(host: host));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('profile-a')));
    await tester.pumpAndSettle();
    expect(host.lastAction, 'open');
    expect(host.lastId, 'a');
    await tester.tap(find.byKey(const ValueKey('profile-b')));
    await tester.pumpAndSettle();
    expect(host.lastAction, 'upgrade');
    expect(host.lastId, 'b');
  });
  testWidgets('Direct full access uses native open action for both profiles',
      (tester) async {
    final host = FakeHost('connections', full: true, direct: true);
    await tester.pumpWidget(FileHubUi(host: host));
    await tester.pumpAndSettle();
    expect(find.text('Upgrade'), findsNothing);
    await tester.tap(find.byKey(const ValueKey('profile-b')));
    await tester.pumpAndSettle();
    expect(host.lastAction, 'open');
    expect(host.lastId, 'b');
  });
  testWidgets('failed snapshot can be retried without fake profile data',
      (tester) async {
    final host = FakeHost('connections')..fail = true;
    await tester.pumpWidget(FileHubUi(host: host));
    await tester.pumpAndSettle();
    expect(find.text('NAS A'), findsNothing);
    host.fail = false;
    await tester.tap(find.byIcon(Icons.refresh));
    await tester.pumpAndSettle();
    expect(find.text('NAS A'), findsOneWidget);
  });
}
