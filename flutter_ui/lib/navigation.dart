import 'package:flutter/services.dart' show HapticFeedback;
import 'compact_text.dart';
import 'package:material_ui/material_ui.dart';
import 'main.dart';
import 'remote_editor.dart' show CloudLogo;

typedef Navigate = Future<void> Function(String action, [String? id]);

final class FavoriteSummary {
  const FavoriteSummary(this.id, this.label, this.original);
  factory FavoriteSummary.fromMap(Map<Object?, Object?> row) =>
      FavoriteSummary(row['id'] as String, row['label'] as String,
          row['original'] as String? ?? '');
  final String id, label, original;
}

class HomeNavigation extends StatelessWidget {
  const HomeNavigation({super.key, required this.data, required this.busy, required this.action});
  final HubSnapshot data;
  final bool busy;
  final Navigate action;

  String _capacity() {
    if (data.freeBytes < 0) return '';
    var amount = data.freeBytes.toDouble();
    const units = ['B','KB','MB','GB','TB'];
    var unit=0;
    while (amount >= 1024 && unit < units.length-1) { amount/=1024; unit++; }
    return '${data.t('available')} ${amount.toStringAsFixed(unit == 0 ? 0 : 1)} ${units[unit]}';
  }

  Widget _heading(String key) => Padding(
    padding: const EdgeInsets.fromLTRB(3,18,3,9),
    child: Text(data.t(key), style: const TextStyle(fontSize: 15,fontWeight: FontWeight.w700)),
  );

  Widget _tile(String key, IconData icon, Color color, double width, {String subtitle=''}) =>
      SizedBox(width: width, height: 137,
        child: Card(margin: EdgeInsets.zero, child: InkWell(
          key: ValueKey('home-$key'), borderRadius: BorderRadius.circular(12),
          onTap: busy ? null : () => action(key),
          child: Padding(padding: const EdgeInsets.symmetric(horizontal: 5,vertical: 9),
            child: Column(mainAxisAlignment: MainAxisAlignment.center,children: [
              Icon(icon,color:color,size:43),
              const SizedBox(height:9),
              Text(data.t(key),maxLines:2,overflow:TextOverflow.ellipsis,
                textAlign:TextAlign.center,style:const TextStyle(fontSize:13,fontWeight:FontWeight.w600)),
              if (subtitle.isNotEmpty) ...[
                const SizedBox(height:4),
                Flexible(child:Text(subtitle,maxLines:2,overflow:TextOverflow.ellipsis,textAlign:TextAlign.center,
                  style:const TextStyle(fontSize:10))),
              ],
            ]),
          ),
        )),
      );

  @override
  Widget build(BuildContext context) => Align(
    alignment:Alignment.topCenter,
    child:ConstrainedBox(constraints:const BoxConstraints(maxWidth:840),
      child:LayoutBuilder(builder:(context,constraints) {
        final columns=constraints.maxWidth < 340 ? 2 : 3;
        final width=(constraints.maxWidth-32-(columns-1)*10)/columns;
        return ListView(padding:const EdgeInsets.fromLTRB(16,4,16,85),children:[
          _heading('storageHeading'),
          Wrap(spacing:10,runSpacing:10,children:[
            _tile('storage',Icons.storage_rounded,const Color(0xff0b8f83),width,subtitle:_capacity()),
            if (data.hasSd) _tile('sd',Icons.sd_storage_outlined,const Color(0xff6b8cbe),width),
            _tile('downloads',Icons.download_rounded,const Color(0xff4989cf),width),
            _tile('images',Icons.photo_library_outlined,const Color(0xffa66bc4),width),
            _tile('audio',Icons.library_music_outlined,const Color(0xff159e90),width),
            _tile('videos',Icons.video_library_outlined,const Color(0xffdd7662),width),
          ]),
          const SizedBox(height:8), const Divider(),
          _heading('features'),
          Wrap(spacing:10,runSpacing:10,children:[
            _tile('videoShelf',Icons.movie_outlined,const Color(0xffdd7662),width),
            _tile('comicShelf',Icons.collections_bookmark_outlined,const Color(0xffa66bc4),width),
            _tile('textShelf',Icons.menu_book_outlined,const Color(0xff6b99c2),width),
          ]),
          _heading('connections'),
          Card(child:ListTile(key:const ValueKey('home-connections'),
            leading:const Icon(Icons.dns_outlined,size:32),
            title:Text(data.t('network')),
            subtitle:Text(data.profileError ? data.t('profileError') :
              data.profiles.isEmpty ? data.t('networkManage') : '${data.profiles.length}'),
            trailing:const Icon(Icons.chevron_right),
            onTap:busy?null:()=>action('connections'))),
        ]);
      }),
    ),
  );
}

class DrawerNavigation extends StatelessWidget {
  const DrawerNavigation({super.key,required this.data,required this.busy,
      required this.action,required this.reorder});
  final HubSnapshot data;
  final bool busy;
  final Navigate action;
  final Future<void> Function(List<String>) reorder;

  Widget _link(String name, IconData icon,{String? label}) => ListTile(
      key:ValueKey('drawer-$name'),dense:true,minTileHeight:40,visualDensity:VisualDensity.compact,
      leading:Icon(icon,size:20),title:CompactText(label??data.t(name),style:const TextStyle(fontSize:13)),
      onTap:busy?null:()=>action(name));

  Widget _section(String label, IconData icon, String toggle, bool expanded) => ListTile(
    key:ValueKey('drawer-$toggle'),dense:true,minTileHeight:40,visualDensity:VisualDensity.compact,
    leading:Icon(icon,size:20),
    title:CompactText(data.t(label),style:const TextStyle(fontWeight:FontWeight.w600,fontSize:13)),
    trailing:Icon(expanded?Icons.expand_more:Icons.chevron_right),
    onTap:busy?null:()=>action(toggle),
  );

  @override
  Widget build(BuildContext context) => Column(children:[
    Expanded(child:CustomScrollView(key:const ValueKey("drawer-scroll"),slivers:[
      SliverList.list(children:[
      ListTile(key:const ValueKey('drawer-home'),minTileHeight:50,
        contentPadding:const EdgeInsets.symmetric(horizontal:18),
        leading:const Icon(Icons.home_outlined,size:24),
        title:Text(data.t('home'),style:const TextStyle(fontSize:15,fontWeight:FontWeight.w600)),
        onTap:busy?null:()=>action('home')),
      const Divider(height:1),
      _section('favorites',Icons.star_border,'toggleFavorites',data.favoritesExpanded),
      ]),
      if (data.favoritesExpanded) ...[
        if (data.favoriteError)
          SliverToBoxAdapter(child:Padding(padding:const EdgeInsets.all(16),child:Text(data.t('favoriteError'))))
        else if (data.favorites.isEmpty)
          SliverToBoxAdapter(child:Padding(padding:const EdgeInsets.all(16),child:Text(data.t('favoriteEmpty'))))
        else
          SliverReorderableList(
            key:const ValueKey('drawer-favorite-order'),
            itemCount:data.favorites.length,
            onReorderStart:(_)=>HapticFeedback.vibrate(),
            proxyDecorator:(child,index,animation)=>AnimatedBuilder(
              animation:animation,child:child,
              builder:(context,dragged)=>Material(
                elevation:6*animation.value,
                color:Theme.of(context).colorScheme.surface,
                child:dragged)),
            onReorderItem:(oldIndex,newIndex) {
              if (busy||oldIndex==newIndex) return;
              final ids=data.favorites.map((f)=>f.id).toList();
              ids.insert(newIndex,ids.removeAt(oldIndex));
              reorder(ids);
            },
            itemBuilder:(context,index) {
              final f=data.favorites[index];
              return Material(key:ValueKey('drawer-favorite-${f.id}'),
                color:Theme.of(context).colorScheme.surface,
                child:Row(children:[
                  ReorderableDelayedDragStartListener(index:index,enabled:!busy,
                    child:Semantics(label:data.t('reorder'),child:SizedBox(
                      key:ValueKey('drawer-drag-${f.id}'),width:54,height:48,
                      child:const Icon(Icons.drag_handle,size:22)))),
                  Expanded(child:ReorderableDelayedDragStartListener(index:index,enabled:!busy,
                    child:InkWell(onTap:busy?null:()=>action('favorite',f.id),
                      child:ConstrainedBox(constraints:const BoxConstraints(minHeight:48),
                        child:Padding(padding:const EdgeInsets.symmetric(vertical:8),
                          child:Column(mainAxisAlignment:MainAxisAlignment.center,
                            crossAxisAlignment:CrossAxisAlignment.start,children:[
                              Text(f.label,maxLines:1,overflow:TextOverflow.ellipsis,style:const TextStyle(fontSize:14)),
                              if(f.original.isNotEmpty)Text(f.original,maxLines:1,overflow:TextOverflow.ellipsis,
                                style:TextStyle(fontSize:11,color:Theme.of(context).colorScheme.onSurfaceVariant)),
                            ])))))),
                  IconButton(icon:const Icon(Icons.more_vert),tooltip:data.t('manage'),
                    onPressed:busy?null:()=>action('favoriteManage',f.id)),
                ]));
            }),
        SliverToBoxAdapter(child:Padding(padding:const EdgeInsets.only(left:20),
          child:_link('favorites',Icons.list_alt_outlined,label:data.t('favorites')))),
      ],
      SliverList.list(children:[
      const Divider(height:1),
      _section('network',Icons.dns_outlined,'toggleNetwork',data.networkExpanded),
      if (data.networkExpanded) ...[
        if (data.profileError)
          Padding(padding:const EdgeInsets.all(16),child:Text(data.t('profileError'))),
        for (final p in data.profiles)
          ListTile(key:ValueKey('drawer-profile-${p.id}'),
            dense:true,minTileHeight:44,visualDensity:VisualDensity.compact,
            contentPadding:const EdgeInsets.only(left:28,right:12),
            leading:CloudLogo(p.protocol,size:20,fallback:const Icon(Icons.folder_shared_outlined,size:20)),
            title:Text(p.label,maxLines:1,overflow:TextOverflow.ellipsis,style:const TextStyle(fontSize:13)),
            subtitle:Text(p.protocol=='GoogleDrive'?'Google Drive':p.protocol,style:const TextStyle(fontSize:11)),
            onTap:busy?null:()=>action(p.allowed?'open':'connections',p.allowed?p.id:null)),
        Padding(padding:const EdgeInsets.only(left:20),
          child:_link('connections',Icons.add_circle_outline,label:data.t('networkManage'))),
      ],
      ]),
    ])),
    if(!data.direct&&!data.full)
      ListTile(key:const ValueKey('drawer-upgrade'),dense:true,minTileHeight:48,
        visualDensity:VisualDensity.compact,
        leading:const SizedBox(width:24,height:24,child:CustomPaint(painter:_CrownPainter())),
        title:CompactText(data.t('premiumUpgrade'),style:const TextStyle(fontSize:13,fontWeight:FontWeight.w600)),
        onTap:busy?null:()=>action('upgrade')),
  ]);
}

class _CrownPainter extends CustomPainter{
  const _CrownPainter();
  @override
  void paint(Canvas canvas,Size size){
    canvas.save();
    canvas.scale(size.width/24,size.height/24);
    final shape=Path()..moveTo(2.5,7)..lineTo(7.5,12)..lineTo(12,4)
      ..lineTo(16.5,12)..lineTo(21.5,7)..lineTo(19,19)..lineTo(5,19)..close();
    canvas.drawPath(shape,Paint()..color=const Color(0xffd8a34e));
    canvas.restore();
  }
  @override
  bool shouldRepaint(covariant _CrownPainter oldDelegate)=>false;
}
