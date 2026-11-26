import 'package:material_ui/material_ui.dart';
class CompactText extends StatelessWidget {
  const CompactText(this.value,{super.key,this.style});
  final String value;
  final TextStyle? style;
  @override
  Widget build(BuildContext context)=>Tooltip(message:value,child:FittedBox(
    fit:BoxFit.scaleDown,alignment:AlignmentDirectional.centerStart,
    child:Text(value.replaceAll(RegExp(r'[\r\n]+'),' '),maxLines:1,softWrap:false,style:style)));
}