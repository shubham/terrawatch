# CI → Play Store automated deployment

Written 2026-08-17, right after Play identity verification cleared. The workflow file
(.github/workflows/release.yml) and gradle hooks (ciVersionCode, CI upload-signing config)
are already committed; this doc is the one-time setup + how releases work afterward.

## How a release works once set up

```
git tag v1.0.0 && git push origin v1.0.0
        │
        ▼
release.yml: secrets check → jvmTest+migration verify → signed AAB
(versionCode = 10 + run number, upload key from secret) → upload to
Play "beta" (closed) track with R8 mapping.txt → AAB kept as run artifact
```

Manual alternative: Actions → "Release to Play" → Run workflow → pick track
(internal = just you, before testers; beta = the closed track with the 14-day clock).
Ordinary pushes/merges never release — ci.yml owns those; release.yml fires only on
`v*` tags and manual dispatch.

## One-time setup (order matters)

### 1. Play Console prerequisites (user, in the browser)
- Create the app (see ACCOUNT-SETUP.md step 1.6) and complete App content.
- **Play App Signing**: accept the default (Google manages the app signing key) when
  the first AAB is uploaded. Our keystore below is only the *upload key*.
- **First AAB upload must be manual** (Play quirk: the API can't create the very first
  release for an app). Task 8 builds it; user drags it into the closed-track page once.
  Every release after that is CI's job.

### 2. Upload keystore (one command, run locally — Task 8 does this)
```bash
keytool -genkeypair -v -keystore upload.jks -alias terrawatch-upload \
  -keyalg RSA -keysize 4096 -validity 9125 \
  -dname "CN=YugMa, O=YugMa" -storepass <PICK_STRONG_PASS> -keypass <SAME_OR_OTHER>
base64 -i upload.jks | pbcopy   # -> UPLOAD_KEYSTORE_B64 secret
```
Keep upload.jks + passwords in a password manager. If ever lost, Play App Signing
lets you request an upload-key reset (that's why we let Google hold the app key).

### 3. Play API service account (user; same JSON also feeds RevenueCat)
Play Console → Setup → API access → link/create Google Cloud project → create service
account → in Play Console Users & permissions grant it: "Release to testing tracks"
(+ "View app information"). Download the JSON key once; it becomes both the
PLAY_SERVICE_ACCOUNT_JSON secret AND the file RevenueCat's dashboard asks for.

### 4. GitHub secrets (repo → Settings → Secrets and variables → Actions)
| Secret | Value |
|---|---|
| UPLOAD_KEYSTORE_B64 | base64 of upload.jks |
| KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD | from step 2 (alias: terrawatch-upload) |
| PLAY_SERVICE_ACCOUNT_JSON | the JSON file's full contents |
| REVENUECAT_API_KEY | goog_… (RevenueCat dashboard) |
| ADMOB_APP_ID | ca-app-pub-…~… |
| ADMOB_BANNER_UNIT | ca-app-pub-…/… |

CI builds write monetization.properties from these — the real IDs never live in git.

### 5. Dry run
Actions → Release to Play → Run workflow → track: internal. Confirms signing, versioning,
API auth end-to-end without touching testers. Then tags drive beta releases.

## Design notes
- **versionCode** = 10 + GitHub run number: monotonic, no version-bump commits, offset
  clears the manually-uploaded codes (1..3). versionName stays gradle-owned (bump it +
  SettingsScreen.APP_VERSION together, per the in-code reminder).
- **Signing fallback**: without CI_KEYSTORE_PATH env (i.e., everywhere except release.yml),
  the release build type keeps debug signing so local R8 smokes still work. The env-gated
  `upload` signingConfig only exists on the runner.
- **mapping.txt** uploads with every AAB → Play Console deobfuscates crash reports.
- **Track policy**: tags → beta (closed track, testers, 14-day clock). internal = manual
  dispatch only, for pre-tester sanity checks. Production promotion stays a human action
  in Play Console (deliberate — no auto-prod while the account is new).
- Uploader action: r0adkll/upload-google-play@v1.1.3 (pinned).

## Current status / what's pending
- [x] Workflow + gradle hooks committed (this commit)
- [x] GitHub Pages live → privacy URL for App content
- [ ] User: create app + App content + closed track + 12 testers (ACCOUNT-SETUP.md 1.6-1.8)
- [ ] User: AdMob + RevenueCat + service account (ACCOUNT-SETUP.md 2-3, step 3 above)
- [ ] Task 8 ("keys are in"): keystore generation, secrets population, first manual AAB
  upload, dry-run internal release, then tag-driven releases live
