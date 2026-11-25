# FileHub AIO Play v1.0.2 — signed internal test preparation

## Account owner tasks

1. Create the Play Console developer account. Use the genuine personal or organization identity. Complete developer identity checks and payment profile setup directly in Google.
2. Create a free app named FileHub AIO. Its application ID is com.filehubaio.mobile. The existing Direct ID is separate.
3. In Monetize > Monetization setup > Licensing, copy the app-specific public licensing key. This is not an upload signing key.
4. In Monetize with Play > Products > One-time products, create the permanent non-consumable product filehub_full_version, with one Buy purchase option and Korean price KRW 3,900. Set the availability and actual localized prices in the console.
5. Create an unpublished Android app in AdMob and a banner plus interstitial ad unit. Keep the app ID and both ad unit IDs. Link the store listing when available. Configure consent messages and applicable privacy requirements.
6. Complete Play App Signing with Google managing the app signing key. The independent FileHub upload key signs only the bundles uploaded to Play. Choose the default Google-generated app signing key for this new Play-only application. Use the exported public certificate if the console requests it.
7. Configure license-test accounts, upload the signed internal-test AAB, and test real purchase/restore/refund flows on devices. Do not promote the internal test artifact to production.

Public values the developer can safely provide: AdMob app ID, banner ad unit ID, interstitial ad unit ID, Play app licensing public key. Do not share account credentials, OTPs, payment details, private signing key or service-account private credentials.

## Explicit build modes

- Default: `:app:bundlePlayRelease` produces an unsigned development candidate.
- Signed internal test: `:app:bundlePlayRelease -PfilehubPlayInternalTest=true -PfilehubPlayReleaseSigningConfig=<private properties path>`. Official demo ad units only; no production ad requests. Independent signing configuration is required. If no Play licensing public key has been configured, purchase verification remains disabled.
- Signed production: `:app:bundlePlayRelease -PfilehubPlayProduction=true -PfilehubPlayReleaseSigningConfig=<private properties path>`, with the three actual AdMob IDs and the Play licensing public key configured. Preflight is automatically attached to the release build. It rejects missing or demo advertising IDs, different publisher prefixes, repeated ad units, invalid public keys, missing signing configuration and known Direct key reuse.

Do not enable internal and production mode together. Debug always uses test ads. Keep any publisher configuration outside the repository; the upload keystore and its password must be backed up securely by the owner. A public upload certificate may be shared when needed. Actual Play account setup, release upload, testing, production review and live advertising have not been performed in this development step.

See PLAY_MONETIZATION_V101.md for feature policy and remaining release checks. Store review, complete localization, privacy disclosures, license notices, external decoder strategy, cloud provider terms and real-device tests are still outstanding.
