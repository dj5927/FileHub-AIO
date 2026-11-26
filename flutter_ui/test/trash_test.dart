import 'package:filehub_flutter/trash.dart';
import 'package:filehub_flutter/explorer.dart' show FileTypeIcon;
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

const labels={
  'restore':'Restore','erase':'Delete permanently','empty':'Empty recycle bin',
  'emptyMessage':'The recycle bin is empty.','loading':'Checking...',
  'refresh':'Refresh','selectAll':'Select all','selectedCount':'%d selected',
  'ok':'OK','cancel':'Cancel','error':'Failed',
  'restoreConfirm':'Restore %d items to their original locations?',
  'eraseConfirm':'Permanently delete %d items? This cannot be undone.',
};
Map<Object?,Object?> data(List<Map<String,Object?>> rows,{String revision='r1',bool loading=false})=>{
  'revision':revision,'summary':'2 items · 18.16 MB','retention':'Automatically delete after 7 days',
  'loading':loading,'rows':rows,
};
const rows=<Map<String,Object?>>[
  {'id':'a','name':'BlueDragon_Android_V055_NIGHTLY_10B9B84F_KR30_RS80_V52PAD.apk',
    'origin':'Internal storage / Documents','detail':'2026.11.26 · 18.16 MB','icon':'android','available':true},
  {'id':'b','name':'Offline volume.txt','origin':'ABCD / Backup','detail':'Storage disconnected','icon':'text','available':false},
];
class Host implements TrashHost{
  final calls=<List<Object>>[];
  Map<Object?,Object?> next=data([],revision:'r2');
  @override Future<Map<Object?,Object?>> trashCommand(String revision,String command,List<String> ids)async{
    calls.add([revision,command,List<String>.of(ids)]);return next;
  }
}
Widget screen(Host host,Map<Object?,Object?> model,{bool dark=false})=>MaterialApp(
  theme:ThemeData(colorSchemeSeed:const Color(0xff00675e),brightness:dark?Brightness.dark:Brightness.light),
  home:Scaffold(body:TrashScreen(data:model,labels:labels,host:host)));
Finder key(String id)=>find.byKey(ValueKey('trash-$id'));
void main(){
  testWidgets('trash uses shared icons, compact long filenames, and safe disabled selection',(tester)async{
    tester.view.physicalSize=const Size(320,480);tester.view.devicePixelRatio=1;
    addTearDown(tester.view.resetPhysicalSize);addTearDown(tester.view.resetDevicePixelRatio);
    final host=Host();
    for(final dark in [false,true]){
      await tester.pumpWidget(screen(host,data(rows),dark:dark));await tester.pumpAndSettle();
      expect(find.byType(FileTypeIcon),findsNWidgets(2));
      final filename=tester.widget<Text>(find.text(rows[0]['name'] as String));
      expect(filename.maxLines,2);expect(filename.overflow,TextOverflow.ellipsis);
      expect(tester.widget<Checkbox>(key('check-b')).onChanged,isNull);
      expect(tester.widget<InkWell>(key('restore')).onTap,isNull);
      expect(tester.getRect(key('empty')).bottom,lessThanOrEqualTo(480));
      expect(tester.takeException(),isNull);
    }
    await tester.tap(key('select-all'));await tester.pumpAndSettle();
    expect(tester.widget<Checkbox>(key('check-a')).value,isTrue);
    expect(tester.widget<Checkbox>(key('check-b')).value,isFalse);
    await tester.tap(key('restore'));await tester.pumpAndSettle();
    expect(find.text('Restore 1 items to their original locations?'),findsOneWidget);
    await tester.tap(find.text('Cancel'));await tester.pumpAndSettle();
    expect(host.calls,isEmpty);
    await tester.tap(key('restore'));await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton,'Restore'));await tester.pumpAndSettle();
    expect(host.calls,[['r1','restore',['a']]]);
    expect(find.text('The recycle bin is empty.'),findsOneWidget);
    expect(tester.widget<InkWell>(key('erase')).onTap,isNull);
  });
  testWidgets('empty and permanent deletion only submit confirmed available IDs',(tester)async{
    final host=Host();
    await tester.pumpWidget(screen(host,data(rows)));await tester.pumpAndSettle();
    await tester.tap(key('empty'));await tester.pumpAndSettle();
    expect(find.text('Permanently delete 1 items? This cannot be undone.'),findsOneWidget);
    await tester.tap(find.text('Cancel'));await tester.pumpAndSettle();
    expect(host.calls,isEmpty);
    await tester.tap(key('entry-a'));await tester.pumpAndSettle();
    await tester.tap(key('erase'));await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton,'Delete permanently'));await tester.pumpAndSettle();
    expect(host.calls,[['r1','erase',['a']]]);
  });
  testWidgets('list revision change while confirm is open prevents stale operation',(tester)async{
    final host=Host();
    await tester.pumpWidget(screen(host,data(rows)));await tester.pumpAndSettle();
    await tester.tap(key('empty'));await tester.pumpAndSettle();
    await tester.pumpWidget(screen(host,data(rows,revision:'r3')));await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton,'Empty recycle bin'));await tester.pumpAndSettle();
    expect(host.calls,isEmpty);
  });
}
