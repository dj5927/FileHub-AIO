import 'package:filehub_flutter/remote_editor.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';

class FakeEditor implements RemoteEditorHost {
  Map<String,Object?>? saved;
  String? help;
  bool logged=false,classic=false,fail=false;
  @override
  Future<void> saveRemote(Map<String,Object?> draft) async {
    if(fail)throw PlatformException(code:'host');
    saved=Map.of(draft);
  }
  @override
  Future<bool> loginTeraBox() async {logged=true;return true;}
  @override
  Future<void> cloudHelp(String protocol) async {help=protocol;}
  @override
  Future<void> classicEditor()async {classic=true;}
}

Map<String,Object?> data({String protocol='SMB',bool editing=false})=>{
  'editing':editing,'protocol':protocol,'name':editing?'My NAS':'',
  'host':editing?'nas.example.test':'','port':remotePorts[remoteProtocols.indexOf(protocol)],
  'root':'share','username':'user','hasSecret':editing,'ssl':true,'ftpTls':0,
  'ftpCharset':'UTF-8','sftpCharset':'UTF-8','ftpPasv':true,'readOnly':false,
  'ftpOptions':['UTF-8','CP949'],'ftpLabels':['UTF-8','CP949'],
  'sftpOptions':['UTF-8','GBK'],'sftpLabels':['UTF-8','GBK'],
};
Future<void> mount(WidgetTester tester,FakeEditor host,Map<String,Object?> d)async{
  await tester.pumpWidget(MaterialApp(home:RemoteEditorScreen(data:d,labels:const {
    'err_host':'Invalid host','save':'Save','keep':'Keep credentials',
    'sessionReady':'Session acquired','replace':'Change credentials',
  },host:host,onCancel:(){})));
  await tester.pumpAndSettle();
}
Finder key(String s)=>find.byKey(ValueKey('editor-$s'));
Future<void> see(WidgetTester tester,Finder item)async{
  await tester.scrollUntilVisible(item,130,
    scrollable:find.descendant(of:find.byType(ListView),matching:find.byType(Scrollable)).first);
  await tester.ensureVisible(item);
  await tester.pumpAndSettle();
}
Future<void> selectProtocol(WidgetTester tester,String type)async{
  await tester.tap(key('protocol'));await tester.pumpAndSettle();
  await tester.tap(find.text(type).last);await tester.pumpAndSettle();
}
void main(){
  testWidgets('compact picker opens WebDAV form, retains advanced settings and saves',(tester)async{
    final host=FakeEditor();var closed=false;
    tester.view.physicalSize=const Size(390,780);tester.view.devicePixelRatio=1;
    addTearDown(tester.view.resetPhysicalSize);addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(MaterialApp(home:Scaffold(body:RemoteEditorOverlay(
      data:data(),labels:const {'editorAdd':'Add storage','networkHeading':'Network','cloudHeading':'Cloud','advanced':'Advanced','save':'Save'},
      host:host,onClose:()=>closed=true))));
    await tester.pumpAndSettle();
    expect(find.byType(ProtocolIcon),findsNWidgets(8));
    await tester.tap(find.byKey(const ValueKey('choose-WebDAV')));await tester.pumpAndSettle();
    expect(key('host'),findsOneWidget);
    expect(tester.widget<TextField>(key('port')).controller!.text,'443');
    expect(key('root'),findsNothing);
    await tester.enterText(key('host'),'nas.example.test');
    await tester.enterText(key('secret'),'test');
    await tester.tap(key('advanced'));await tester.pumpAndSettle();
    expect(key('root'),findsOneWidget);expect(key('readOnly'),findsOneWidget);
    await tester.tap(key('save'));await tester.pumpAndSettle();
    expect(host.saved!['protocol'],'WebDAV');expect(closed,isTrue);
    expect(tester.takeException(),isNull);
  });

  for(final protocol in ['SMB','FTP']){
    testWidgets('$protocol anonymous switch clears and disables credentials',(tester)async{
      final host=FakeEditor();await mount(tester,host,data(protocol:protocol));
      expect(tester.getTopLeft(key('name')).dy,lessThan(tester.getTopLeft(key('host')).dy));
      await tester.enterText(key('username'),'typed-user');
      await tester.enterText(key('secret'),'typed-pass');
      await see(tester,key('anonymous'));await tester.tap(key('anonymous'));await tester.pumpAndSettle();
      for(final field in ['username','secret']){
        final input=tester.widget<TextField>(key(field));
        expect(input.enabled,isFalse);expect(input.controller!.text,isEmpty);
      }
      await tester.tap(key('save'));await tester.pumpAndSettle();
      expect(host.saved!['username'],'');expect(host.saved!['anonymous'],isTrue);
      expect(host.saved!['secretMode'],'empty');expect(host.saved!.containsKey('secret'),isFalse);
    });
  }

  testWidgets('edit keeps existing secret out of UI and save payload',(tester)async{
    final host=FakeEditor();await mount(tester,host,data(editing:true));
    expect(key('secret'),findsNothing);
    await tester.tap(key('save'));await tester.pumpAndSettle();
    expect(host.saved!['secretMode'],'keep');expect(host.saved!.containsKey('secret'),isFalse);
  });
  testWidgets('endpoint change requires replacement field and masks input',(tester)async{
    final host=FakeEditor();await mount(tester,host,data(editing:true));
    await tester.enterText(key('host'),'other.example.test');await tester.pumpAndSettle();
    await see(tester,key('secret'));
    expect(tester.widget<TextField>(key('secret')).obscureText,isTrue);
    await tester.enterText(key('secret'),'replacement-123');await tester.pumpAndSettle();
    await tester.tap(key('save'));await tester.pumpAndSettle();
    expect(host.saved!['secretMode'],'replace');expect(host.saved!['secret'],'replacement-123');
  });
  testWidgets('FTP and SFTP show their own controls and correct ports',(tester)async{
    final host=FakeEditor();await mount(tester,host,data());
    await selectProtocol(tester,'FTP');
    expect(tester.widget<TextField>(key('port')).controller!.text,'21');
    await see(tester,key('ftpTls'));expect(key('ftpTls'),findsOneWidget);
    await tester.tap(key('ftpTls'));await tester.pumpAndSettle();
    await tester.tap(find.text('FTPS Implicit TLS (990)').last);await tester.pumpAndSettle();
    await tester.tap(key('save'));await tester.pumpAndSettle();
    expect(host.saved!['port'],'990');expect(host.saved!['ftpTls'],2);
    await tester.drag(find.byType(ListView),const Offset(0,1500));await tester.pumpAndSettle();
    await selectProtocol(tester,'SFTP');
    expect(tester.widget<TextField>(key('port')).controller!.text,'22');
    await see(tester,key('sftpCharset'));expect(key('ftpTls'),findsNothing);
  });
  testWidgets('TeraBox uses native session without returning secret; MYBOX hides host and root',(tester)async{
    final host=FakeEditor();await mount(tester,host,data(protocol:'TeraBox'));
    expect(key('host'),findsNothing);
    await see(tester,key('login'));await tester.tap(key('login'));await tester.pumpAndSettle();
    expect(host.logged,isTrue);expect(find.text('Session acquired'),findsOneWidget);
    await tester.tap(key('save'));await tester.pumpAndSettle();
    expect(host.saved!['secretMode'],'session');expect(host.saved!.containsKey('secret'),isFalse);
    await tester.drag(find.byType(ListView),const Offset(0,1000));await tester.pumpAndSettle();
    await selectProtocol(tester,'MYBOX');
    expect(key('root'),findsNothing);expect(key('host'),findsNothing);expect(key('login'),findsNothing);
    await see(tester,key('secret'));expect(key('secret'),findsOneWidget);
  });
  testWidgets('Google Drive uses account authorization without password or host fields',(tester)async{
    final host=FakeEditor();await mount(tester,host,data(protocol:'GoogleDrive'));
    expect(key('host'),findsNothing);expect(key('username'),findsNothing);
    expect(key('secret'),findsNothing);expect(key('name'),findsOneWidget);
    await tester.enterText(key('name'),'My Google Drive');
    await tester.tap(key('save'));await tester.pumpAndSettle();
    expect(host.saved!['protocol'],'GoogleDrive');
    expect(host.saved!['name'],'My Google Drive');
    expect(host.saved!['secret'],anyOf(isNull,''));
  });
  testWidgets('OneDrive uses Microsoft authorization without password or host fields',(tester)async{
    final host=FakeEditor();await mount(tester,host,data(protocol:'OneDrive'));
    expect(key('host'),findsNothing);expect(key('username'),findsNothing);
    expect(key('secret'),findsNothing);expect(key('name'),findsOneWidget);
    await tester.enterText(key('name'),'My OneDrive');
    await tester.tap(key('save'));await tester.pumpAndSettle();
    expect(host.saved!['protocol'],'OneDrive');
    expect(host.saved!['name'],'My OneDrive');
    expect(host.saved!['secret'],anyOf(isNull,''));
  });
  testWidgets('four official logo assets retain the existing 21dp chooser icon',(tester)async{
    final protocols=['GoogleDrive','OneDrive','MYBOX','TeraBox'];
    await tester.pumpWidget(MaterialApp(home:Scaffold(body:Row(children:protocols.map((p)=>ProtocolIcon(p)).toList()))));
    await tester.pumpAndSettle();
    expect(find.byType(Image),findsNWidgets(4));
    for(final image in tester.widgetList<Image>(find.byType(Image))){
      expect(image.width,21);expect(image.height,21);expect(image.fit,BoxFit.contain);
    }
    expect(tester.takeException(),isNull);
  });
  testWidgets('native validation failure retains the form',(tester)async{
    final host=FakeEditor()..fail=true;await mount(tester,host,data());
    await tester.enterText(key('host'),'bad');await tester.tap(key('save'));await tester.pumpAndSettle();
    expect(find.text('Invalid host'),findsOneWidget);expect(host.saved,isNull);
    expect(tester.widget<TextField>(key('host')).controller!.text,'bad');
  });
}
