# Release Checklist — Network Technician Toolkit

**Target Version:** `28.0` (`versionCode = 28`)  
**Application ID:** `com.aistudio.networktoolkit.arqxcv`  
**Namespace:** `com.example.networktoolkit`  
**Verified Debug Build Command:** `./gradlew :app:assembleDebug --no-daemon --console=plain`  
**Verified Debug APK Output Path:** `app/build/outputs/apk/debug/app-debug.apk`

---

## 1. Build, Lint & Automated Verification (Verified in Current Build Environment)

- [x] **Confirm application version and version code** — Verified in `app/build.gradle.kts` (`versionName = "28.0"`, `versionCode = 28`).
- [x] **Confirm application ID** — Verified in `app/build.gradle.kts` (`applicationId = "com.aistudio.networktoolkit.arqxcv"`, `namespace = "com.example.networktoolkit"`).
- [x] **Verify debug build** — Verified via `./gradlew :app:assembleDebug --no-daemon --console=plain` (`app/build/outputs/apk/debug/app-debug.apk`).
- [x] **Verify release compilation** — Verified via `:app:compileReleaseKotlin` and `:app:compileReleaseJavaWithJavac` (`BUILD SUCCESSFUL`). (Note: final release APK/AAB packaging requires signing environment variables.)
- [x] **Run lint** — Verified via `:app:lintVitalRelease` with 0 errors (`./gradlew :app:lintDebug --no-daemon --console=plain` available for debug lint checks).
- [x] **Run unit tests** — Verified via `:app:testDebugUnitTest` (210/210 tests passed across 17 test suites; 0 failures, 0 skipped).
- [x] **Review repository for secrets** — Audited source, Gradle configs, and resources; no API keys, access tokens, or release signing credentials are included, and `.env.example`, `metadata.json`, `debug.keystore*`, `local.properties`, and APKs are excluded via `.gitignore`. (Git repository initialized locally on branch `main` with `origin` connected to the public GitHub repository; the initial commit is locally amended for verified GitHub noreply attribution, no commit or project files have been pushed to GitHub yet, and the remote still contains the original AI Studio template commit pending explicit history reconciliation.)
- [x] **Review README and documentation** — Created/updated `README.md`, `RELEASE_CHECKLIST.md`, `RELEASE_NOTES_TEMPLATE.md`, `PROJECT_STRUCTURE.md`, and `.gitignore`.

---

## 2. Pre-Publication & Owner Actions Remaining

- [ ] **Verify release signing configuration** — Confirm `KEYSTORE_PATH`, `STORE_PASSWORD`, and `KEY_PASSWORD` are configured in your release build environment and match your upload key (`keyAlias = "upload"`).
- [ ] **Confirm signing keystore is securely backed up** — Verify the production `.jks` upload keystore and credentials are backed up in secure offline/vault storage outside Git.
- [ ] **Generate signed APK or AAB** — Run `./gradlew :app:bundleRelease --no-daemon --console=plain` (or `:app:assembleRelease`) with production signing credentials configured.
- [ ] **Verify APK installation on a physical device** — Install and launch on physical Android hardware (Android 8.0+ / API 26+) to validate runtime behavior outside Robolectric.
- [ ] **Test critical network diagnostic features** — Manually verify Wi-Fi Scanner, Signal Mapper, Live Connectivity Monitor, Network Info (with and without VPN), Ping, Traceroute, DNS Lookup, LAN Scanner, Speed Test, iPerf3, Subnet Calculator, and Network Notes on real Wi-Fi and cellular networks.
- [ ] **Verify report exports and privacy redaction** — Confirm SAF file saving (`ACTION_CREATE_DOCUMENT`) and `FileProvider` sharing work on device and redact SSIDs/BSSIDs properly.
- [x] **Finalize license copyright notice** — Apache License 2.0 finalized in `LICENSE` (`Copyright 2026 Joel Blackburn`) and referenced in `README.md`.
- [ ] **Create GitHub release notes** — Populate release notes using `RELEASE_NOTES_TEMPLATE.md` and tag the release commit in Git/GitHub.
- [ ] **Preserve the final release artifact securely** — Archive the signed `.aab` / `.apk` and mapping/build provenance outside the source repository.
