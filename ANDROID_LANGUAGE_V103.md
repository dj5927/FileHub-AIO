# Android locale and notice update — v0.1.120 Direct / v1.0.3 Play

## User-facing changes
- Settings > General > Interface > Display language: System, Korean, English, Japanese, Simplified Chinese. The default follows Android; unsupported system languages use English for translated entries. Manual selection persists locally and recreates only the settings screen. Main refreshes its current folder on resume; the existing transfer service is not stopped.
- All eleven Activity classes use an app-localized context for newly opened screens. Only locale/direction are overridden, not display dimensions or orientation.
- Shared offline catalogue contains reviewed translations for native menus, settings, readers, connection dialogs, purchasing, and the Office host. The audit covers direct UI literals and reviewed option arrays; it is not a proof that every dynamic or third-party string is translated. The upstream rhwp editor supports Korean and English; Japanese and Chinese use its English UI. User filenames, document contents and protocol/persisted keys are unchanged.
- Settings > General > Interface > Open source & licenses includes a per-edition dependency inventory, bundled project license and upstream notice text extracted from actual JAR/AAR files plus retained web component notices. Inventory rows without a bundled notice require upstream review. This is not a complete legal clearance or a substitute for corresponding-source obligations.
- Direct retains existing codec/RAR/OTA functionality. Play stays separate, including free/full purchase policy, test advertising, and external-player/archive routing. No production account credentials are required for these changes.

## Verification and release boundary
Run scripts/audit_localization.py, both unit-test tasks, Direct signed release APK, Play debug APK and upload-signed internal AAB. Test on a device by cycling all language modes, closing/reopening the app, returning from settings while browsing a NAS folder, folding/unfolding, and viewing each reader and the Office UI. No production release is authorized by this development step.

Outstanding: actual-device layout and language tests, remaining dynamic messages, full upstream license and corresponding-source review, in-process external decoder feasibility, production Play and AdMob configuration, private Direct OTA deployment. Existing public Direct and PC release tags remain unchanged.
