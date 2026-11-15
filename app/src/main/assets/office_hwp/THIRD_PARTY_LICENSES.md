# Third-Party Licenses

rhwp 프로젝트가 사용하는 서드파티 라이브러리 및 리소스의 라이선스 목록이다.

아랍어 문맥 측정의 결합 속성 표는 Unicode 16.0.0의
[ArabicShaping.txt](https://www.unicode.org/Public/16.0.0/ucd/ArabicShaping.txt)에서 생성했다.
Copyright © 2024 Unicode, Inc.이며 [Unicode License v3](assets/licenses/Unicode-3.0.txt)를 적용한다.
원문 SHA-256은 `764f420cedfc8b43d9fec251c957a5d55fc45d40f6573f162990ed1dce7e36e0`이다.

Print JPEG 인코더의 원래 라이선스·저작권 고지는 `vendor/rhwp-jpeg-encoder/LICENSE-*`에 있다.
Print JPEG 디코더의 선택 변환은 공개 Intel MediaSDK 정수 IDCT(MIT)를 Rust로 번역하고,
IJG 보간 규칙을 재표현한다. Intel 저작권·MIT 고지는
`src/renderer/image_resolver/print_jpeg_idct.rs`와 `licenses/intel-mediasdk/LICENSE`, IJG 원문과 배포 조건은
`vendor/rhwp-zune-jpeg/README.ijg`, 해당 구현은 `vendor/rhwp-zune-jpeg/src/ijg_upsampler.rs`와
최종 JPEG 재생의 `vendor/rhwp-zune-jpeg/src/ijg_replay.rs`,
변경 설명은 `vendor/rhwp-zune-jpeg/RHWP_PATCHES.md`에 있다.
This software is based in part on the work of the Independent JPEG Group.

기준 파일:

- `Cargo.toml` / `Cargo.lock` (`rhwp` v0.9.0)
- `rhwp-studio/package.json` / `rhwp-studio/package-lock.json`
- `rhwp-chrome/package.json` / `rhwp-chrome/package-lock.json`
- `rhwp-firefox/package.json` / `rhwp-firefox/package-lock.json`
- `rhwp-vscode/package.json` / `rhwp-vscode/package-lock.json`
- `assets/fonts/FONTS.md`

---

## Rust 크레이트

### 직접 의존성

| 크레이트 | 버전 | 라이선스 | 저장소 | 비고 |
|---------|------|---------|--------|------|
| aes | 0.9.2 | MIT OR Apache-2.0 | RustCrypto/block-ciphers | HWPX 암호화 |
| base64 | 0.23.1 | MIT OR Apache-2.0 | marshallpierce/rust-base64 | Base64 인코딩 |
| blake3 | 1.8.6 | CC0-1.0 OR Apache-2.0 OR Apache-2.0 WITH LLVM-exception | BLAKE3-team/BLAKE3 | 해시/진단 |
| byteorder | 1.5.0 | Unlicense OR MIT | BurntSushi/byteorder | 바이너리 endian 처리 |
| cbc | 0.2.1 | MIT OR Apache-2.0 | RustCrypto/block-modes | HWPX 암호화 모드 |
| cfb | 0.14.0 | MIT | mdsteele/rust-cfb | OLE Compound File |
| cipher | 0.5.2 | MIT OR Apache-2.0 | RustCrypto/traits | 암호 trait |
| codepage | 0.1.2 | Apache-2.0 OR MIT | hsivonen/codepage | 코드페이지 처리 |
| console_error_panic_hook | 0.1.7 | Apache-2.0/MIT | rustwasm/console_error_panic_hook | WASM panic hook |
| crc32fast | 1.5.0 | MIT OR Apache-2.0 | srijs/rust-crc32fast | HWP3 암호 키 검증 |
| des | 0.9.0 | MIT OR Apache-2.0 | RustCrypto/block-ciphers | HWP3 암호화 |
| ed25519-dalek | 2.2.0 | BSD-3-Clause | dalek-cryptography/curve25519-dalek | capsule 서명 |
| embedded-io | 0.7.1 | MIT OR Apache-2.0 | rust-embedded/embedded-hal | IO trait |
| encoding_rs | 0.8.35 | (Apache-2.0 OR MIT) AND BSD-3-Clause | hsivonen/encoding_rs | 문자 인코딩 |
| flate2 | 1.1.9 | MIT OR Apache-2.0 | rust-lang/flate2-rs | 압축/해제 |
| getrandom | 0.4.3 | MIT OR Apache-2.0 | rust-random/getrandom | 서명 키 난수 생성 |
| hmac | 0.13.0 | MIT OR Apache-2.0 | RustCrypto/MACs | HWPX 무결성 |
| image | 0.25.10 | MIT OR Apache-2.0 | image-rs/image | BMP/JPEG/PNG 디코딩 |
| rhwp-zune-jpeg | 0.5.15-rhwp.1 | (MIT OR Apache-2.0 OR Zlib) AND IJG | etemesi254/zune-image, IJG | 요청별 할당 예산·인스턴스별 Print 변환; 기본 PDF import decoder는 원래 변환 유지 |
| js-sys | 0.3.102 | MIT OR Apache-2.0 | rustwasm/wasm-bindgen | WASM JS interop |
| office_oxide | 0.1.9 | MIT OR Apache-2.0 | yfedoseev/office_oxide | `pdf-import` optional 호환성 제약; PDF 전이 의존성 |
| paste | 1.0.15 | MIT OR Apache-2.0 | dtolnay/paste | 매크로 보조 |
| pbkdf2 | 0.13.0 | MIT OR Apache-2.0 | RustCrypto/password-hashes | HWPX 키 파생 |
| pcx | 0.2.5 | MIT OR Apache-2.0 OR WTFPL | kryptan/pcx | PCX 이미지 디코딩 |
| pdf-writer | 0.12.1 | MIT OR Apache-2.0 | typst/pdf-writer | PDF 출력 |
| rhwp-pdf-import-backend | 0.3.78-rhwp.1 | MIT OR Apache-2.0 | edwardkim/rhwp | `pdf-import` optional; `pdf_oxide 0.3.78` 기반 내부 보안 패치, 코드 import alias `pdf_oxide` |
| quick-xml | 0.41.0 | MIT | tafia/quick-xml | HWPX XML 파싱 |
| resvg | 0.47.0 | Apache-2.0 OR MIT | linebender/resvg | Native/WASM 공통 WMF Print surface, native-skia SVG raster |
| roxmltree | 0.21.1 | MIT OR Apache-2.0 | RazrFalcon/roxmltree | XML 검증·정규화 |
| serde | 1.0.229 | MIT OR Apache-2.0 | serde-rs/serde | 직렬화 |
| serde_json | 1.0.151 | MIT OR Apache-2.0 | serde-rs/json | JSON 직렬화 |
| sha1 | 0.11.0 | MIT OR Apache-2.0 | RustCrypto/hashes | HWPX 암호 키 파생 |
| sha2 | 0.11.0 | MIT OR Apache-2.0 | RustCrypto/hashes | 서명·CAS 해시 |
| skia-safe | 0.156.0 | MIT | rust-skia/rust-skia | native-skia PNG/PDF backend (optional) |
| snafu | 0.9.2 | MIT OR Apache-2.0 | shepmaster/snafu | 에러 처리 |
| strum | 0.28.0 | MIT | Peternator7/strum | enum derive |
| subsecond | 0.7.10 | MIT OR Apache-2.0 | DioxusLabs/dioxus | 개발 핫패치 (optional) |
| subsetter | 0.2.6 | MIT OR Apache-2.0 | typst/subsetter | 폰트 subset |
| rhwp-jpeg-encoder | 0.7.1-rhwp.1 (upstream 0.7.1) | (MIT OR Apache-2.0) AND IJG | vstroebel/jpeg-encoder, rhwp 보정 | Print JPEG의 성분 순서·채널별 sampling·정수 DCT/YCbCr 전달. 원본 및 패치 고지 유지 |
| rhwp-svg2pdf | 0.13.0-rhwp.1 (upstream 0.13.0) | MIT OR Apache-2.0 | typst/svg2pdf, rhwp 보정 | SVG → PDF. 원저작자·패치 고지 유지 |
| svgtypes | 0.16.1 | Apache-2.0 OR MIT | linebender/svgtypes | SVG 값 파싱 |
| ttf-parser | 0.25.1 | MIT OR Apache-2.0 | harfbuzz/ttf-parser | 폰트 파싱 |
| unicode-properties | 0.1.4 | MIT OR Apache-2.0 | unicode-rs/unicode-properties | 유니코드 문자 분류 |
| unicode-bidi | 0.3.18 | MIT OR Apache-2.0 | servo/unicode-bidi | 텍스트 표시 방향과 논리 구간의 시각 원점 |
| unicode-bidi-mirroring | 0.4.0 | MIT OR Apache-2.0 | https://github.com/RazrFalcon/unicode-bidi-mirroring |
| unicode-segmentation | 1.13.3 | MIT OR Apache-2.0 | unicode-rs/unicode-segmentation | grapheme/word segmentation |
| unicode-width | 0.2.2 | MIT OR Apache-2.0 | unicode-rs/unicode-width | 문자 폭 계산 |
| usvg | 0.45.1 / 0.47.0 | Apache-2.0 OR MIT | linebender/resvg | Native SVG 파싱 / 공통 WMF surface의 resvg 파싱 |
| wasm-bindgen | 0.2.125 | MIT OR Apache-2.0 | rustwasm/wasm-bindgen | WASM binding |
| wasm-bindgen-test | 0.3.75 | MIT OR Apache-2.0 | rustwasm/wasm-bindgen | WASM 테스트 |
| web-sys | 0.3.102 | MIT OR Apache-2.0 | rustwasm/wasm-bindgen | Web API binding |
| zip | 8.6.0 | MIT | zip-rs/zip2 | HWPX ZIP 처리 |

### PDF 코덱의 내부 보정판 (#8183·#8174)

현재 #8174 v2-S02의 기본 빌드는 다음 코덱을 포함한다. `pdf-jbig2`는 JBIG2와 내부 MMR 처리용
CCITT를, `pdf-jpeg2000`은 JPEG 2000을 선택한다. backend의 전체 rendering은 켜지 않는다.

| 내부 패키지 | 원본 버전 | 라이선스 | 보완 범위 |
| --- | --- | --- | --- |
| `rhwp-hayro-jbig2` `0.3.0-rhwp.1` | `hayro-jbig2` `0.3.0` | Apache-2.0 OR MIT | 사전·segment·bitmap의 예약과 반복 작업 |
| `rhwp-hayro-ccitt` `0.3.0-rhwp.1` | `hayro-ccitt` `0.3.0` | Apache-2.0 OR MIT | JBIG2 MMR의 changing-line 저장과 디코딩 작업 |
| `rhwp-hayro-jpeg2000` `0.4.0-rhwp.1` | `hayro-jpeg2000` `0.4.0` | Apache-2.0 OR MIT | tile·component·wavelet 저장과 디코딩 작업 |
| `rhwp-codec-resource` `0.1.0` | RHWP 자체 코드 | MIT | 동기 요청의 fallible 예약·논리 작업량 연결 |

원저작자는 Laurenz Stampfl이며 원본은 [LaurenzV/hayro](https://github.com/LaurenzV/hayro)이다.
각 `vendor/rhwp-hayro-*/`의 `NOTICE`, `LICENSE-*`, `UPSTREAM_FILES.json`에 원본 고지와
공식 `.crate` archive SHA-256·파일별 원본 해시를 보존했다. JPEG 2000의 ICC 자료 고지는
`vendor/rhwp-hayro-jpeg2000/assets/README.md`와 `assets/LICENSE.txt`를 함께 배포한다.
이는 RHWP 보정판이며 공식 hayro 릴리스가 아니다. 패키지는 게시 전 로컬 후보이고
실제 crates.io 게시 승인·이름 확정은 #8174의 절차를 따른다.

### 전체 Rust 의존성 요약

전체 의존성은 현재 `Cargo.toml` / `Cargo.lock`과 선택한 feature·target을 함께 기준으로 확인한다.
`cargo metadata --locked --features pdf-import --filter-platform wasm32-unknown-unknown` 등으로
해당 구성의 resolve graph를 확인하며, lockfile에 존재한다는 이유만으로 최종 배포물에 포함된다고
간주하지 않는다. 과거 workspace의 package 개수는 현재 의존성 총수로 사용하지 않는다.

주요 라이선스군:

- MIT / Apache-2.0 dual license 계열
- MIT 단독
- Apache-2.0 OR MIT
- BSD-2-Clause / BSD-3-Clause
- BSD-3-Clause AND MIT (`brotli`, PDF 전이 의존성)
- Zlib
- Unlicense
- 0BSD
- ISC
- Unicode-DFS-2016
- CC0-1.0 / Apache-2.0 WITH LLVM-exception (`blake3`)
- WTFPL 대체 선택지 포함 (`pcx`, MIT/Apache 선택 가능)

> 모든 Rust 의존성은 MIT, Apache-2.0, BSD, Zlib, Unlicense, 0BSD, ISC 등 permissive license 계열이거나
> permissive option을 포함하며, rhwp의 MIT 라이선스와 호환된다.

### PDF 입력 의존성의 고지

`pdf-import`는 기본 포함이며, 이를 통해 `rhwp-pdf-import-backend =0.3.78-rhwp.1`과
`office_oxide =0.1.9`를 모두 `default-features = false`로 사용한다.
`office_oxide` 직접 버전 제약은 `pdf_oxide 0.3.78`과의 컴파일 호환성을 위한 것이며,
rhwp가 Office 입력 형식 지원을 추가했다는 뜻은 아니다.
내부 backend는 Brotli private scratch를 요청별로 제한하기 위해
`brotli-decompressor =6.0.1`도 직접 사용한다. 해당 crate의 라이선스 표기는
`BSD-3-Clause/MIT`이며 기존 `brotli 9.0.0`의 `BSD-3-Clause AND MIT`와 구별한다.
감사한 Native/WASM 구성에서는 `std`만 활성화하고 `unsafe`/FFI feature는 활성화하지 않는다.

내부 backend는 메인테이너의 별도 승인에 따라 `pdf_oxide 0.3.78`을 보존한 보안 패치다.
크기 최적화용 소스 추출은 하지 않는다. 수정판을 공식 PDFOxide release로 표시하지 않으며,
원본·변경 범위·배포 제한은 [패치 기록](vendor/rhwp-pdf-import-backend/RHWP_PATCHES.md),
원본 파일별 hash는 `vendor/rhwp-pdf-import-backend/UPSTREAM_FILES.json`에 보존한다.
원래 MIT/Apache 라이선스·NOTICE·상표 정책도 해당 directory에 유지한다.
내부 Rust dependency는 `rlib`만 생성한다. 원본 C API·writer 소스는 보존하지만 이 내부
package를 독립 cdylib/staticlib 제품으로 배포하는 계약은 제공하지 않는다.
manifest는 crates.io 게시 후보로 준비되어 있지만, 실제 게시 일정과 공개 registry 소비 검증은 제품 릴리즈와 별도로 확정한다.

아래 고지는 해당 버전 crate 배포본의 `NOTICE`와 `LICENSE-MIT` 원문이다.
두 crate의 재배포에는 dual license 중 MIT 선택지를 적용한다. 이 고지와 각 전이 의존성의
라이선스·저작권 고지는 실제 Native/WASM 배포물의 문서 또는 고지 자료에도 함께 제공해야 한다.
제품명·패키지명·로고의 상표 사용 권한은 소스 코드 라이선스와 별개다.

#### PDFOxide 0.3.78 NOTICE

```text
PDFOxide
Copyright (c) 2025-2026 Yury Fedoseev and the PDFOxide project contributors

PDFOxide is the product; "pdf_oxide" is the name of its packages/crate.

This product is licensed under the terms of the MIT license OR the
Apache License, Version 2.0, at your option. See LICENSE-MIT and
LICENSE-APACHE for the full license texts.

Copyright in the PDFOxide code base is held by Yury Fedoseev and the project's
contributors; contributions are made under the project's Contributor License
Agreement (see CLA.md) and/or the Developer Certificate of Origin (see
CONTRIBUTING.md), under which contributors retain ownership of their
contributions and license them to the project.

"PDFOxide" (the product name) and "pdf_oxide" (the package name), together with
any associated logo, are trademarks of Yury Fedoseev and are not covered by the
source-code license. See TRADEMARKS.md.

Portions of this software incorporate third-party open-source components; their
respective licenses are listed in the dependency manifests (Cargo.toml /
Cargo.lock and the per-binding package manifests).
```

#### PDFOxide 0.3.78 MIT License

```text
Copyright (c) 2025-present Yury Fedoseev

Permission is hereby granted, free of charge, to any
person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the
Software without restriction, including without
limitation the rights to use, copy, modify, merge,
publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software
is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice
shall be included in all copies or substantial portions
of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF
ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT
SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
```

#### OfficeOxide 0.1.9 NOTICE

```text
OfficeOxide
Copyright (c) 2026 Yury Fedoseev and the OfficeOxide project contributors

OfficeOxide is the product; "office_oxide" is the name of its packages/crate.

This product is licensed under the terms of the MIT license OR the
Apache License, Version 2.0, at your option. See LICENSE-MIT and
LICENSE-APACHE for the full license texts.

Copyright in the OfficeOxide code base is held by Yury Fedoseev and the project's
contributors; contributions are made under the project's Contributor License
Agreement (see CLA.md) and/or the Developer Certificate of Origin (see
CONTRIBUTING.md), under which contributors retain ownership of their
contributions and license them to the project.

"OfficeOxide" (the product name) and "office_oxide" (the package name), together with
any associated logo, are trademarks of Yury Fedoseev and are not covered by the
source-code license. See TRADEMARKS.md.

Portions of this software incorporate third-party open-source components; their
respective licenses are listed in the dependency manifests (Cargo.toml /
Cargo.lock and the per-binding package manifests).
```

#### OfficeOxide 0.1.9 MIT License

```text
Copyright (c) 2026 Yury Fedoseev

Permission is hereby granted, free of charge, to any
person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the
Software without restriction, including without
limitation the rights to use, copy, modify, merge,
publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software
is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice
shall be included in all copies or substantial portions
of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF
ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT
SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
```

#### PDFOxide 동봉 폰트

`pdf_oxide 0.3.78` 소스에는 DejaVu Sans Regular/Bold가 `include_bytes!`로 선언되어 있다.
이 자산이 포함된 배포에 대비해 crate의 `src/fonts/assets/LICENSE-DejaVu` 고지를 보존한다.
소스에 존재하는 것과 최종 WASM에 포함되는지는 구분하며, 포함 여부를 크기 측정만으로 단정하지 않는다.
별도 `cjk-form-fonts` / `cjk-render-fallback` 기능의 Droid Sans Fallback·Noto Emoji는
현재 활성화하지 않는다. 해당 기능 도입 시 별도 폰트 고지도 함께 추가한다.

```text
DejaVu Fonts — Copyright (c) 2003 by Bitstream, Inc. All Rights Reserved.
Bitstream Vera is a trademark of Bitstream, Inc.

Permission is hereby granted, free of charge, to any person obtaining a copy
of the fonts accompanying this license ("Fonts") and associated documentation
files (the "Font Software"), to reproduce and distribute the Font Software,
including without limitation the rights to use, copy, merge, publish,
distribute, and/or sell copies of the Font Software, and to permit persons to
whom the Font Software is furnished to do so, subject to the following
conditions:

The above copyright and trademark notices and this permission notice shall be
included in all copies of one or more of the Font Software typefaces.

The Font Software may be modified, altered, or added to, and in particular the
designs of glyphs or characters in the Fonts may be modified and additional
glyphs or characters may be added to the Fonts, only if the fonts are renamed
to names not containing either the words "Bitstream" or the word "Vera".

THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF COPYRIGHT, PATENT,
TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL BITSTREAM OR THE GNOME FOUNDATION
BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, INCLUDING ANY GENERAL,
SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF THE USE OR INABILITY TO
USE THE FONT SOFTWARE OR FROM OTHER DEALINGS IN THE FONT SOFTWARE.
```

---

## npm 패키지

### npm 배포 패키지

| 패키지 | 버전 | 라이선스 | 용도 |
|--------|------|---------|------|
| @rhwp/core | 0.8.6 | MIT | WASM parser/renderer API |
| @rhwp/editor | 0.8.6 | MIT | iframe 기반 웹 에디터 wrapper |

### rhwp-studio

| 패키지 | Lock 버전 | 라이선스 | 용도 |
|--------|-----------|---------|------|
| canvaskit-wasm | 0.42.0 | BSD-3-Clause | CanvasKit 렌더링 backend |
| @noble/hashes | 2.4.0 | MIT | 렌더 리소스 키와 문서 digest |
| @types/chrome | 0.2.5 | MIT | Chrome API 타입 |
| pixelmatch | 7.2.0 | ISC | 시각 diff 픽셀 비교 |
| pngjs | 7.0.0 | MIT | 시각 