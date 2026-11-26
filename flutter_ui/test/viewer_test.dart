import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:filehub_flutter/viewer.dart';

class FakeViewer implements ViewerHost{
  final calls=<String>[];
  @override
  Future<void> viewerCommand(String nonce,String command,[int? value])async{
    calls.add('$nonce:$command:$value');
  }
}
const labels={'back':'Back','previous':'Previous','next':'Next','play':'Play',
  'pause':'Pause','settings':'Settings','quick':'Quick navigation','classicViewer':'Classic controls'};
Map<Object?,Object?> state(String mode,List<String> enabled,{int progress=-1,bool playing=false})=>{
  'mode':mode,'nonce':'current','label':'Page 3 / 10','bg':0xff101923,
  'fg':0xffffffff,'progress':progress,'playing':playing,'enabled':enabled,
};
Widget wrap(FakeViewer host,Map<Object?,Object?> data,double height,{double width=320})=>
  MaterialApp(home:Scaffold(body:Align(alignment:Alignment.topLeft,child:SizedBox(
    width:width,height:height,child:ViewerPanel(data:data,labels:labels,host:host)))));
Finder btn(String name)=>find.byKey(ValueKey('viewer-$name'));

void main(){
  testWidgets('document save uses native command and respects lock and busy state',(tester)async{
    final host=FakeViewer();
    Map<Object?,Object?> document({bool locked=false,bool busy=false})=>{
      ...state('document',['back','classic',if(!locked&&!busy)'save',if(!busy)'search']),
      'label':'sample.xlsx','hasSave':true,'hasSearch':true,'readOnly':locked,'busy':busy,
    };
    await tester.pumpWidget(wrap(host,document(),49));
    await tester.tap(btn('save'));await tester.pump();
    expect(host.calls,['current:save:null']);
    await tester.pumpWidget(wrap(host,document(locked:true),49));
    expect(tester.widget<IconButton>(btn('save')).onPressed,isNull);
    await tester.tap(btn('search'));await tester.pump();
    expect(host.calls,['current:save:null','current:search:null']);
    await tester.pumpWidget(wrap(host,document(busy:true),49));
    expect(tester.widget<IconButton>(btn('save')).onPressed,isNull);
    expect(tester.widget<IconButton>(btn('search')).onPressed,isNull);
    expect(tester.takeException(),isNull);
  });
  testWidgets('image header uses native sequence commands and blocks unavailable neighbor',(tester)async{
    final host=FakeViewer();
    await tester.pumpWidget(wrap(host,state('image',['back','next','classic']),44));
    expect(tester.widget<IconButton>(btn('previous')).onPressed,isNull);
    await tester.tap(btn('next'));await tester.pump();
    await tester.tap(btn('back'));await tester.pump();
    expect(btn('classic'),findsNothing);
    expect(host.calls,['current:next:null','current:back:null']);
    expect(tester.takeException(),isNull);
  });
  testWidgets('video toolbar updates playback icon from native state',(tester)async{
    final host=FakeViewer();
    await tester.pumpWidget(wrap(host,state('video',['back','settings','play','classic']),49));
    expect(find.byIcon(Icons.play_arrow),findsOneWidget);
    await tester.tap(btn('play'));await tester.pump();
    await tester.pumpWidget(wrap(host,state('video',['back','settings','play','classic'],playing:true),49));
    expect(find.byIcon(Icons.pause),findsOneWidget);
    await tester.tap(btn('settings'));await tester.pump();
    expect(host.calls,['current:play:null','current:settings:null']);
    expect(tester.takeException(),isNull);
  });
  testWidgets('comic and TXT seek only submits the final bounded value',(tester)async{
    final host=FakeViewer();
    await tester.pumpWidget(wrap(host,state('readerSlider',['previous','next','quick','seek','classic'],progress:300),71));
    final seek=btn('seek');
    expect(tester.widget<Slider>(seek).value,300);
    tester.widget<Slider>(seek).onChangeStart!(300);
    tester.widget<Slider>(seek).onChanged!(750);
    await tester.pump();
    expect(host.calls,isEmpty);
    expect(tester.widget<Slider>(seek).value,750);
    await tester.pumpWidget(wrap(host,state('readerSlider',['previous','next','quick','seek','classic'],progress:350),71));
    expect(tester.widget<Slider>(seek).value,750);
    tester.widget<Slider>(seek).onChangeEnd!(750);
    await tester.pump();
    expect(host.calls,['current:seek:750']);
    expect(tester.takeException(),isNull);
  });
  testWidgets('EPUB and PDF retain page information and native quick navigation',(tester)async{
    final host=FakeViewer();
    await tester.pumpWidget(wrap(host,state('reader',['previous','next','quick','classic']),53));
    expect(find.byType(Slider),findsNothing);
    await tester.tap(btn('quick'));await tester.pump();
    await tester.tap(btn('next'));await tester.pump();
    expect(host.calls,['current:quick:null','current:next:null']);
    await tester.pumpWidget(wrap(host,state('reader',['classic']),53));
    expect(tester.widget<IconButton>(btn('next')).onPressed,isNull);
    expect(tester.takeException(),isNull);
  });
}
