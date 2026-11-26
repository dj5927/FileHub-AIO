# Android release license and provider review — 2026-11-25

Scope: FileHub AIO Play v1.0.6 / Direct v0.1.121. This is a technical
inventory and distribution checklist, not legal clearance. No change to
the app's GPL-3.0 license, supported providers or commercial model is made.

## Verified upstream notices

The exact release commits below were resolved from upstream Git tags.
Original license/NOTICE texts are stored in THIRD_PARTY_LICENSES/pinned,
with origin URLs and SHA-256 in manifest.json. Gradle checks those hashes
and selects the files using the actual resolved runtime coordinates.
The Play bundle gate verifies the bundled notices byte-for-byte.

| Component | Version | License | Upstream commit | Edition |
| --- | --- | --- | --- | --- |
| jcifs-ng | 2.1.10 | LGPL-2.1 | 01ad55aee9511b9b466c5d0f586bfa9314469334 | Both |
| ZXing Core | 3.5.4 | Apache-2.0 | f651b0a0375676e47144f73397dddff8868b0e4c | Both |
| XZ for Java | 1.12 | 0BSD | 107a519fac1e6789101ad9c234afe3dc407be7f5 | Both |
| NextLib | 1.11.1-0.19.0 | GPL-3.0 | 539411ccb4740251252a2cc8896c6579d0c43c3d | Direct |
| Junrar | 8.1.2 | UnRAR custom license | b9b61cedd7c0b7fcbd2ab6b4cc6413e2bb9e481d | Direct |

The v1.0.5 Play runtime inventory contained 105 distinct coordinates;
75 had no embedded notice in the scanned JAR/AAR. Missing embedded
notices are not proof of missing license obligations. Maven metadata
identifies many AndroidX, Media3, SSHJ, OkHttp, Okio, and Kotlin
dependencies as Apache-2.0; Google Ads, Billing, UMP and several
transitive Google SDKs declare Android SDK terms. Continue the
version-specific review of remaining modules and embedded web/fonts.
Do not label proprietary SDKs as GPL or Apache merely because the app
or another dependency is open source.

## Corresponding source and distribution issues

- jcifs-ng is bundled in both variants (including Play's R8-processed
  DEX). Obtain exact corresponding source and supply the rights and
  materials required by the LGPL, including a practical modified-library
  rebuild/relink path where applicable. A license text alone is insufficient.
- NextLib is GPL-3.0; its exact release source archive is retained.
  The upstream ffmpeg/setup.sh specifies FFmpeg 9.0.1, dav1d 1.5.4,
  mbedTLS 3.6.7, libass 0.17.5, FreeType 2.14.1, FriBidi 1.0.16,
  HarfBuzz 14.4.0, fontconfig 2.16.0, Expat 2.8.4 and libunibreak 7.0.
  Complete the corresponding-source archive for the actual native build
  (including relevant patches, scripts, selected components, and any
  required installation information) and separately assess patent rights.
  Do not equate GPL compliance with codec patent clearance.
- Junrar's UnRAR conditions prohibit using the code to recreate the
  proprietary RAR compression algorithm. Preserve the original notice and
  review the combination's GPL compatibility. Play excludes Junrar.
- The main project is currently GPL-3.0. The Play edition's linked Google
  SDK terms and distribution conditions require a compatibility review;
  removing NextLib and Junrar does not automatically resolve this.
  Do not unilaterally relicense existing code. Any exception or alternate
  licensing must be authorized by all relevant copyright holders.
- GPL software may be sold, but applicable source, redistribution and
  other license rights must be respected. A paid app is not necessarily
  a closed-source app.
- Original source archives for jcifs-ng, NextLib and Junrar are stored
  locally under D:/FileHubAIO-PublisherBuildCache/Android-Licenses-20261125/upstream.
  They are upstream snapshots, not a completed corresponding-source delivery.

References:
https://github.com/AgNO3/jcifs-ng/blob/01ad55aee9511b9b466c5d0f586bfa9314469334/LICENSE
https://github.com/anilbeesetti/nextlib/tree/539411ccb4740251252a2cc8896c6579d0c43c3d
https://github.com/junrar/junrar/blob/b9b61cedd7c0b7fcbd2ab6b4cc6413e2bb9e481d/LICENSE
https://tukaani.org/xz/java.html
https://github.com/androidx/media/blob/release/LICENSE
https://www.gnu.org/licenses/gpl-faq.html
https://developer.android.com/studio/terms

## Cloud provider review

Naver MYBOX: CloudProvider and MyBoxWriteApi use the official
open-api.mybox.naver.com endpoints and a user-created PAT. The official
documentation confirms API usage and limits, including a scheduled
limit review after 2026-12-31. Check HTTP 429 handling and token expiry.
The official API documentation alone does not grant explicit commercial
redistribution permission for this particular ad-supported/paid client.
Confirm the applicable MYBOX-specific terms; do not assume the generic
Naver Open API FAQ is the complete MYBOX agreement.

TeraBox: CloudProvider expressly describes its ndus session API as
unofficial. The Korean TeraBox terms prohibit access through interfaces
that are not publicly supported. Its official integration document
describes application registration, authorization codes and access/
refresh tokens. The existing session-cookie approach is therefore a
release risk. Before public distribution, obtain explicit authorization
or migrate to the supported integration (which needs provider-issued
credentials and potentially a server-side token exchange). If this cannot
be resolved, consider excluding that integration from the public Play
variant. No provider functionality has been changed in this development step.

References:
https://developers.mybox.naver.com/getting-started
https://developers.mybox.naver.com/docs/files_upload
https://www.terabox.app/terms/duty/korean
https://www.terabox.com/integrations/docs/korean

## Publication boundary

The internal builds can be tested now. These artifacts are not approved
for public release. Actual-device regression, additional dynamic strings,
complete corresponding-source materials, legal/provider review, privacy
publication, store declarations and developer registration remain open.
Keep all live ad IDs, payment configuration and private keys for the
final owner-input phase. Do not publish upstream archives or app builds
from this review automatically.
