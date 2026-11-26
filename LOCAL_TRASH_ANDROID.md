# FileHub AIO local recycle bin
Direct 0.1.155 / Play 1.0.38

* Default normal deletion for filesystem and local document-tree selections is a same-storage move. An explicit permanent-delete checkbox bypasses the local trash. NAS and cloud deletion semantics are unchanged.
* Entries use a private AtomicFile journal written before the move and a distinct UUID slot in a hidden folder on the source volume or writable ExternalStorageProvider tree. Direct moves verify device ID. No copy/delete fallback on failed trash moves. Restore refuses collisions; missing original directories are recreated when allowed.
* Removable media is located by the OS StorageVolume UUID, not a stale mount path. Standard writable persisted SAF grants use DocumentsContract.moveDocument when direct access is unavailable. Other document providers or devices that prohibit moves fail without permanent deletion.
* Default 7-day retention; choices 3/5/7/15/30 days or never. Android persisted daily JobScheduler task and opening the trash screen process expired accessible items. Doze, force-stop, power-off or disconnection may delay actual removal. Detached/unavailable entries remain indexed.
* Trash lists original storage/path, deletion time, selection, restore, permanent deletion, empty, and known aggregate bytes. Missing permissions/volumes disable actions. No cloud data is copied to the device.
* The private index is needed for restore. Clearing app data/uninstalling can lose the index; do not clear data with items you want to recover. Hidden folders are not a backup and may be removed by other file managers. Same-name folders are not overwritten.
* Drawer/connection lists reuse the add-connection ProtocolIcon widget; native fallbacks retain protocol-specific icons.
