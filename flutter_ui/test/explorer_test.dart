import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:filehub_flutter/explorer.dart';

const revision='01234567-89ab-cdef-0123-456789abcdef';
Map<Object?,Object?> page(int offset,int total){
  final next=(offset+60).clamp(0,total);
  return {'revision':revision,'offset':offset,'next':next,'total':total,
    'rows':[for(var i=offset;i<next;i++){'id':'$revision:$i','name':'File $i',
      'kind':i==0?'folder':'file','directory':i==0,'selectable':true,
      'secondary':'1 KB','date':'2026.11.25','preview':false,'reading':-1,'purpose':0,'subtitles':0}]};
}
Map<Object?,Object?> snapshot({int total=65,int layout=0,List<int> selection=const []})=>{
  ...page(0,total),'layout':layout,'columns':3,'quota':'','crumbs':[
    {'id':'$revision:0','label':'Root'},{'id':'$revision:1','label':'Subfolder'}],
  'selection':{'count':selection.length,'indices':selection,'inverted':false},
};
class FakeExplorer implements ExplorerHost{
  final offsets=<int>[];
  int total=65;
  bool stale=false;
  @override
  Future<Map<Object?,Object?>> explorerPage(String revision,int offset)async{
    offsets.add(offset);
    return {...page(offset,total),if(stale)'revision':'old'};
  }
  @override
  Future<Map<Object?,Object?>> explorerPreview(String id)async=>{'secondary':'3 items'};
}
Widget screen(FakeExplorer host,Map<Object?,Object?> data,
    Future<void> Function(String,[String?]) action)=>MaterialApp(
  home:Scaffold(body:ExplorerScreen(data:data,labels:const {'more':'More','refresh':'Retry',
    'items':'Items','empty':'Empty','classicExplorer':'Classic'},busy:false,host:host,action:action)));
void main(){
  testWidgets('file categories have distinct visible shapes and badges',(tester)async{
    await tester.pumpWidget(MaterialApp(home:Scaffold(body:Wrap(children:[
      for(final kind in ['spreadsheet','word','presentation','pdf','text','archive','android','design'])
        FileTypeIcon(kind:kind,size:38),
    ]))));
    expect(find.byIcon(Icons.table_chart_outlined),findsOneWidget);
    expect(find.byIcon(Icons.slideshow_outlined),findsOneWidget);
    expect(find.byIcon(Icons.folder_zip_outlined),findsOneWidget);
    for(final mark in ['X','W','P','PDF','TXT','ZIP','APK','ART']){
      expect(find.text(mark),findsOneWidget);
    }
    expect(tester.takeException(),isNull);
  });

  testWidgets('list layouts show subtle separators and grid does not',(tester)async{
    final host=FakeExplorer();
    Future<void> action(String name,[String? id])async{}
    for(final layout in [0,2,1]){
      await tester.pumpWidget(screen(host,snapshot(total:3,layout:layout),action));
      await tester.pumpAndSettle();
      expect(find.byType(FileRowDivider),layout==1?findsNothing:findsNWidgets(2));
      expect(tester.takeException(),isNull);
    }
  });

  test('pages are bounded and malformed page is rejected',(){
    expect(ExplorerPage(page(0,10003)).next,60);
    expect(ExplorerPage(page(10000,10003)).rows.length,3);
    expect(()=>ExplorerPage({...page(0,65),'next':65}),throwsFormatException);
  });
  testWidgets('entry actions and breadcrumb use issued ids without classic switch',(tester)async{
    final host=FakeExplorer();
    final calls=<String>[];
    await tester.pumpWidget(screen(host,snapshot(),(name,[id])async=>calls.add('$name:$id')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Root'));
    await tester.tap(find.text('File 0'));
    await tester.longPress(find.text('File 0'));
    expect(find.byKey(const ValueKey('explorer-classic')),findsNothing);
    expect(calls,['crumb:$revision:0','entryOpen:$revision:0',
      'entrySelect:$revision:0']);
  });
  testWidgets('pagination is lazy and selection retains loaded rows',(tester)async{
    final host=FakeExplorer();final calls=<String>[];
    Future<void> action(String name,[String? id])async=>calls.add('$name:$id');
    await tester.pumpWidget(screen(host,snapshot(),action));
    await tester.pumpAndSettle();expect(host.offsets,isEmpty);
    await tester.scrollUntilVisible(find.text('File 59'),500,
      scrollable:find.descendant(of:find.byType(ExplorerScreen),matching:find.byType(Scrollable)).last);
    await tester.pumpAndSettle();
    if(host.offsets.isEmpty){await tester.tap(find.byKey(const ValueKey('explorer-more')));await tester.pumpAndSettle();}
    expect(host.offsets,[60]);
    await tester.scrollUntilVisible(find.text('File 64'),220,
      scrollable:find.descendant(of:find.byType(ExplorerScreen),matching:find.byType(Scrollable)).last);
    await tester.pumpAndSettle();
    await tester.pumpWidget(screen(host,snapshot(selection:[64]),action));
    await tester.pumpAndSettle();
    final row=tester.widget<ExplorerTile>(find.byKey(const ValueKey('$revision:64')));
    expect(row.selected,isTrue);
    await tester.tap(find.text('File 64'));expect(calls.last,'entrySelect:$revision:64');
  });
  testWidgets('grid, empty and selected directory layout',(tester)async{
    final host=FakeExplorer();
    await tester.pumpWidget(screen(host,snapshot(total:3,layout:1,selection:[0]),(_,[_])async{}));
    await tester.pumpAndSettle();expect(find.byType(GridView),findsOneWidget);
    expect(tester.widget<ExplorerTile>(find.byKey(const ValueKey('$revision:0'))).selected,isTrue);
    await tester.pumpWidget(MaterialApp(home:Scaffold(body:ExplorerScreen(
      key:const ValueKey('empty'),data:snapshot(total:0),labels:const {'empty':'Empty'},
      busy:false,host:host,action:(_,[_])async{}))));
    await tester.pumpAndSettle();expect(find.text('Empty'),findsOneWidget);
  });
}
