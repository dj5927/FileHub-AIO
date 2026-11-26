import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:filehub_flutter/native_panel.dart';

class FakePanel implements NativePanelHost {
  final calls=<String>[];
  @override
  Future<void> panelCommand(String revision,String id,[int? value])async{
    calls.add('$revision:$id:$value');
  }
}

const labels={'apply':'Apply','cancel':'Cancel','classicPanel':'Classic screen','error':'Error','refresh':'Refresh'};
Map<Object?,Object?> panel(String revision,{bool writable=true})=>{
  'revision':revision,'title':'','tabs':['General','Video','Comic','Text'],'selected':0,
  'rows':[
    {'id':'r0','kind':'section','title':'Defaults','enabled':false},
    {'id':'r1','kind':'choice','title':'Speed','detail':'Normal','selected':1,
      'choices':['Slow','Normal','Fast'],'enabled':true},
    {'id':'r2','kind':'action','title':'Save original','enabled':writable},
  ],
};
Widget wrap(FakePanel host,Map<Object?,Object?> data)=>MaterialApp(home:Scaffold(
  body:NativePanel(data:data,labels:labels,host:host,busy:false,reload:()async{})));

void main(){
  testWidgets('shelf displays folders with distinct open and remove controls',(tester)async{
    final host=FakePanel();
    final data=<Object?,Object?>{
      'revision':'shelf1','title':'','tabs':<String>[],'selected':0,'showClassic':false,
      'rows':[{'id':'r0','kind':'folder','title':'Comics','detail':'NAS / Comics','enabled':true}],
    };
    await tester.pumpWidget(wrap(host,data));
    expect(find.byType(Card),findsNothing);
    expect(find.byIcon(Icons.folder_rounded),findsOneWidget);
    expect(tester.getSize(find.byKey(const ValueKey('panel-r0'))).height,lessThanOrEqualTo(64));
    await tester.tap(find.text('Comics'));await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('panel-remove-r0')));await tester.pumpAndSettle();
    expect(host.calls,['shelf1:r0:null','shelf1:r0:remove:null']);
    expect(find.byKey(const ValueKey('panel-classic')),findsNothing);
    expect(tester.takeException(),isNull);
  });
  testWidgets('setting choice only commits after Apply',(tester)async{
    final host=FakePanel();
    await tester.pumpWidget(wrap(host,panel('a')));
    await tester.tap(find.text('Speed'));await tester.pumpAndSettle();
    await tester.tap(find.text('Fast'));await tester.pumpAndSettle();
    expect(host.calls,isEmpty);
    await tester.tap(find.text('Apply'));await tester.pumpAndSettle();
    expect(host.calls,['a:r1:2']);
  });
  testWidgets('disabled save cannot dispatch and tab is scoped to revision',(tester)async{
    final host=FakePanel();
    await tester.pumpWidget(wrap(host,panel('a',writable:false)));
    await tester.tap(find.text('Save original'));await tester.pumpAndSettle();
    expect(host.calls,isEmpty);
    await tester.tap(find.text('Video'));await tester.pumpAndSettle();
    expect(host.calls,['a:tab:1']);
    await tester.pumpWidget(wrap(host,panel('b')));
    await tester.tap(find.text('Save original'));await tester.pumpAndSettle();
    expect(host.calls,['a:tab:1','b:r2:null']);
  });
  testWidgets('stale choice is not sent after native refresh',(tester)async{
    final host=FakePanel();
    await tester.pumpWidget(wrap(host,panel('a')));
    await tester.tap(find.text('Speed'));await tester.pumpAndSettle();
    await tester.tap(find.text('Fast'));await tester.pumpAndSettle();
    await tester.pumpWidget(wrap(host,panel('b')));
    await tester.tap(find.text('Apply'));await tester.pumpAndSettle();
    expect(host.calls,isEmpty);
  });
}
