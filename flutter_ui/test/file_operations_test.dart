import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:filehub_flutter/file_operations.dart';

const revision='01234567-89ab-cdef-0123-456789abcdef';
const labels={'copy':'Copy','move':'Move','rename':'Rename','delete':'Delete',
  'operationMore':'More','paste':'Paste','cancel':'Cancel','items':'Items',
  'moveWaiting':'Ready to move','copyWaiting':'Ready to copy','hide':'Hide',
  'cancelling':'Cancelling','copying':'Copying','moving':'Moving',
  'classicTransfer':'Classic transfer','classicPrompt':'Classic dialog',
  'clearSelection':'Clear','selectAll':'Select all','clearAll':'Deselect all'};
class PromptHost implements FilePromptHost{
  final values=<String>[];
  @override
  Future<void> submitFilePrompt(String nonce,String value)async{values.add('$nonce:$value');}
}
Widget wrap(Widget child)=>MaterialApp(home:Scaffold(body:child));
Finder op(String action)=>find.byKey(ValueKey('operation-$action'));
void main(){
  testWidgets('selection and operations send only current revision',(tester)async{
    final calls=<String>[];
    Future<void> action(String name,[String? id])async{calls.add('$name:$id');}
    final data=<Object?,Object?>{'selected':1,'selectable':5,'allowed':['clearSelection','selectAll','copySelected','renameSelected','deleteSelected','selectedMore']};
    await tester.pumpWidget(wrap(Column(children:[
      FileSelectionHeader(data:data,labels:labels,revision:revision,busy:false,action:action),
      FileOperationBar(data:data,labels:labels,revision:revision,busy:false,action:action),
    ])));
    await tester.tap(op('selectAll'));
    await tester.tap(op('copySelected'));
    await tester.tap(op('moveSelected'));
    await tester.tap(op('renameSelected'));
    await tester.tap(op('deleteSelected'));
    expect(calls,['selectAll:$revision','copySelected:$revision','renameSelected:$revision','deleteSelected:$revision']);
  });
  testWidgets('clipboard across folders and read-only destination controls',(tester)async{
    final calls=<String>[];
    Future<void> action(String name,[String? id])async{calls.add('$name:$id');}
    Widget bar(List<String> allowed)=>wrap(FileOperationBar(
      data:{'selected':0,'staged':3,'destination':true,'moving':true,'allowed':allowed},
      labels:labels,revision:revision,busy:false,action:action));
    await tester.pumpWidget(bar(['clearClipboard']));
    expect(find.text('3 Items · Ready to move'),findsOneWidget);
    await tester.tap(op('pasteClipboard'));
    expect(calls,isEmpty);
    await tester.pumpWidget(bar(['clearClipboard','pasteClipboard']));
    await tester.tap(op('pasteClipboard'));
    await tester.tap(op('clearClipboard'));
    expect(calls,['pasteClipboard:$revision','clearClipboard:$revision']);
  });
  testWidgets('transfer unknown size, 99 cap, hide and cancel',(tester)async{
    final calls=<String>[];
    Future<void> action(String name,[String? id])async{calls.add(name);}
    Widget panel(int percent,{bool cancelling=false,bool finished=false,bool active=true})=>
      wrap(TransferPanel(data:{'active':active,'percent':percent,'finished':finished,
        'cancelling':cancelling,'name':'report.xlsx','amount':'12 B / 12 B','completed':1,'count':2},
        labels:labels,busy:false,action:action));
    await tester.pumpWidget(panel(-1));
    expect(find.text('--%'),findsOneWidget);
    expect(tester.widget<LinearProgressIndicator>(find.byType(LinearProgressIndicator)).value,isNull);
    await tester.pumpWidget(panel(100));
    expect(find.text('99%'),findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('transfer-cancel')));
    await tester.pumpWidget(panel(90,cancelling:true));
    expect(tester.widget<FilledButton>(find.byKey(const ValueKey('transfer-cancel'))).onPressed,isNull);
    await tester.tap(find.byKey(const ValueKey('transfer-hide')));
    expect(calls,['transferCancel','transferHide']);
    await tester.pumpWidget(panel(100,finished:true,active:false));
    expect(find.text('100%'),findsOneWidget);
  });
  testWidgets('rename prompt rejects blank and submits once with nonce',(tester)async{
    final host=PromptHost();
    await tester.pumpWidget(wrap(FilePromptPanel(data:{'nonce':revision,'title':'Rename',
      'message':'','initial':'old.xlsx','positive':'Apply','cancel':true},
      labels:labels,host:host,action:(_,[_])async{})));
    final input=find.byKey(const ValueKey('file-prompt-input'));
    final confirm=find.byKey(const ValueKey('file-prompt-confirm'));
    await tester.enterText(input,'  ');
    await tester.pump();
    expect(tester.widget<FilledButton>(confirm).onPressed,isNull);
    await tester.enterText(input,'new.xlsx');
    await tester.pump();
    await tester.tap(confirm);
    await tester.pump();
    expect(host.values,['$revision:new.xlsx']);
    expect(tester.widget<FilledButton>(confirm).onPressed,isNull);
  });
}
