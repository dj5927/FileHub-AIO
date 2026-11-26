# Direct private OTA
The Direct distribution is now NAS-only. Public GitHub source remains available; Android binary releases are retired, PC distribution is unchanged.
- Direct v0.1.143 / versionCode 144 uses the fixed HTTPS gateway at https://bangtanrun.com/filehub/ota/.
- The access key is supplied at build time via -PfilehubDirectOtaKeyFile pointing to a private local file. Never put the key into source, logs, or GitHub. Public source builds without that private key cannot access this OTA. The distributed APK necessarily contains the scoped key; it can be extracted by a determined recipient. This is a private delivery channel, not DRM.
- Existing Direct users need the migration APK installed once over the existing installation (same signer/package); future checks and downloads use the NAS automatically. The public/preview settings are removed. No fallback to GitHub.
- Deploy scripts/private_ota_gateway.php as index.php in the existing filehub/ota web directory. Store access.sha256 and versioned artifacts in the configured private folder outside the web root.
- After every signed Direct release, create matching source archive, verify signer/package/version, then run scripts/publish_private_ota.py to copy both files and atomically update manifest.json. Verify HTTPS denied/authorized requests and downloaded SHA256.
- Source and third-party license obligations remain applicable; publishing source does not revoke prior copies or prevent recipients from redistributing them. The Play edition remains separate and uses Google Play updates.
