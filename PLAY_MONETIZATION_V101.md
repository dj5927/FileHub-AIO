# FileHub AIO Play v1.0.1 — free and full version

This development branch adds monetization to the Play edition only. Direct remains unrestricted, with no Billing, Ads, or UMP SDK dependency. Do not change the existing public Direct or PC release tags.

## Product policy

- Free install; one-time non-consumable product ID `filehub_full_version`. Set the Korea price to KRW 3,900 in Play Console, not in the APK checkout. The UI uses Google's formatted localized price when available. No subscription, rental, or consumable product.
- Free: one active profile per SMB, FTP (including FTPS), SFTP, WebDAV, TeraBox, MYBOX. Additional restored profiles are retained and visibly locked, rather than deleted. Removing the active profile allows the next saved profile to become active. Existing profiles and backups are preserved.
- Free: built-in Office/HWP viewing and editing restricted. File management and the PDF reader remain available. Paid unlock does not restore codecs, RAR or self-update features deliberately removed from the Play edition.
- Free: anchored banner outside content on supported phone/tablet screens, subject to ad availability and consent; no banners in PiP, TV or QR scanning. Foreground active comics/novels/video time accumulates across screens, with a 10-minute interval. Interstitials appear only at natural breaks (completed episode or volume, EPUB chapter boundary, or return from a finished reader/player), not during uninterrupted playback/scrolling or when leaving the app. Actual ad display resets the timer; failed/no-fill requests do not. No ads are requested after confirmed purchase.
- The new monetization strings are localized to Korean, English, Japanese and Simplified Chinese. This is not a claim that the previous app-wide localization is complete.

## Implementation

Play Billing Library 9.1.0; AdMob legacy Google Mobile Ads SDK 25.5.0; User Messaging Platform 4.0.0. Debug requests use Google's official test ad units. Release without real identifiers disables ad requests; missing Play licensing public key prevents purchase unlocking. No developer bypass or local boolean purchase override. Completed purchases must match package and product and have a valid Google-signed receipt; acknowledge non-consumable purchase, cache verified receipt for offline use, and query ownership on foreground/restore. Successful authoritative absence removes entitlement. For public production, configure secure backend purchase-token verification and refund/revocation handling; client-side receipt validation alone is not a complete fraud-prevention system.

## Console setup before release

1. Register Play app `com.filehubaio.mobile`. Create one-time product `filehub_full_version`, one permanent buy option, no multi-quantity; set South Korea price to 3900 KRW and configure other currencies. Activate product for testers.
2. Obtain this app's public Play licensing RSA key. Configure license testers and upload a signed AAB to an internal test track. Test purchase, pending approval, cancellation, acknowledgment, restore after reinstall, refund and offline behavior.
3. Create AdMob app, anchored banner and interstitial units; configure UMP privacy messages and test devices. Use real ad IDs only in the production build. Complete privacy policy, Play Data Safety, advertising declarations and permission review.
4. Configure independent Play upload signing key in a private properties file outside the repository. Never commit keystore/passwords or service-account credentials. Use Play App Signing. Direct signing key is not reused.

Gradle properties (private local configuration, not hardcoded): `filehubAdMobAppId`, `filehubAdMobBannerId`, `filehubAdMobInterstitialId`, `filehubPlayBillingPublicKey`, `filehubPlayReleaseSigningConfig`.

Build:
- Tests: `:app:testDirectDebugUnitTest :app:testPlayDebugUnitTest`
- Local test: `:app:assemblePlayDebug`
- Candidate: `:app:bundlePlayRelease` (may be unsigned/unconfigured; do not upload)
- Production preflight and package: `:app:verifyPlayProductionConfiguration :app:bundlePlayRelease` with configured properties.

The production preflight intentionally fails if advertising identifiers, billing key or independent signing settings are absent. Actual Play purchase/AdMob delivery and UI/device tests require a configured account and Android device. No production release or payment activation is performed by this development change.
