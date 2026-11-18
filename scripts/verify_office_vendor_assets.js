#!/usr/bin/env node
// Offline reproducibility check for bundled Office renderer libraries.
// No network, accounts, credentials, external files or emulator required.
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const assert = require("node:assert/strict");

const folder = path.resolve(__dirname, "../app/src/main/assets/office_hwp");
const expectations = [
  ["jszip.min.js", "7F839B2D4688B845C105EBF5D2F9803075F91EA0FE72BDAAC176C3A04DD3D2C1", "JSZip v3.10.2"],
  ["docx-preview.min.js", "C4A133C65A112799E35B143C572DFFF9E923A3BF39B5FD3B07A06AA0F8F530C2", "docx-preview"]
];

async function main() {
  for (const [filename, expected, fingerprint] of expectations) {
    const full = path.join(folder, filename);
    const bytes = fs.readFileSync(full);
    const actual = crypto.createHash("sha256").update(bytes).digest("hex").toUpperCase();
    assert.equal(actual, expected, filename + " is not the reviewed official npm bundle");
    assert.ok(bytes.toString("utf8", 0, 280).includes(fingerprint),
      filename + " has an unexpected package header");
    process.stdout.write(filename + " SHA256 verified\n");
  }

  const Zip = require(path.join(folder, "jszip.min.js"));
  const input = "파일 이름과 파일 내용 UTF-8 검증 FileHub AIO v0.1.101";
  const zip = new Zip();
  zip.file("문서/한글.txt", input);
  const encoded = await zip.generateAsync({
    type: "nodebuffer", compression: "DEFLATE"
  });
  const roundTrip = await Zip.loadAsync(encoded);
  assert.equal(await roundTrip.file("문서/한글.txt").async("string"), input);
  process.stdout.write("OFFICE_JSZIP_3102_UTF8_ROUNDTRIP_PASS\n");
}

main().catch(error => {
  process.stderr.write("OFFICE_VENDOR_CHECK_FAILED " + error.message + "\n");
  process.exitCode = 1;
});
