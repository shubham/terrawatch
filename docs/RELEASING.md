# Releasing TerraWatch

Three runbooks: shipping a release through CI, removing the in-app purchase from Play, and
verifying ads actually serve.

Written 2026-09-22, alongside the 1.1.0 ads-only release. Play Console and AdMob reorganise their
navigation regularly — treat UI paths as "near enough" and the *sequence* as the part that matters.

---

## 1. GitHub Actions → Play Store

`.github/workflows/release.yml` is already written and correct. It is dormant only because the 8
repository secrets are unset. This is a wiring job, not a build job.

### 1.1 Create the Play service account

1. **Play Console** → **Setup** → **API access**
2. Link a Google Cloud project (create one if prompted)
3. **Create new service account** → follow the link into Google Cloud Console
4. Cloud Console → **IAM & Admin** → **Service Accounts** → **Create service account**
   (e.g. `play-publisher`). No Cloud IAM roles are needed — Play grants its own permissions.
5. On that account: **Keys** → **Add key** → **Create new key** → **JSON**. It downloads once.
   Treat it like a password.
6. Back in **Play Console → API access** → find the account → **Manage Play Console permissions**
   → grant **Release to testing tracks**, **View app information**, and **Release to production**
   only if you want tags to be able to reach production.

Permission changes can take hours to propagate. A `403` immediately after setup usually means
"wait", not "misconfigured".

### 1.2 Base64 the upload keystore

```bash
base64 -i ~/keys/terrawatch/terrawatch-upload-new.jks | pbcopy
```

### 1.3 Set the 8 secrets

```bash
gh secret set UPLOAD_KEYSTORE_B64 --repo shubham/terrawatch
gh secret set KEYSTORE_PASSWORD   --repo shubham/terrawatch
gh secret set KEY_ALIAS           --repo shubham/terrawatch
gh secret set KEY_PASSWORD        --repo shubham/terrawatch
gh secret set ADMOB_APP_ID        --repo shubham/terrawatch
gh secret set ADMOB_BANNER_UNIT   --repo shubham/terrawatch
gh secret set REVENUECAT_API_KEY  --repo shubham/terrawatch
gh secret set PLAY_SERVICE_ACCOUNT_JSON --repo shubham/terrawatch < ~/Downloads/<service-account>.json
```

`REVENUECAT_API_KEY` may be empty — the app treats a blank key as a fully supported state and
simply never calls `Purchases.configure`. Set it anyway, since the workflow writes all three values
into `monetization.properties` regardless.

Verify with `gh secret list --repo shubham/terrawatch` — expect 8.

### 1.4 Dry run

**Actions** → **Release to Play** → **Run workflow** → track `internal`. This exercises signing,
tests, build and upload without touching beta or production.

### 1.5 Real releases

```bash
git tag v1.1.0 && git push origin v1.1.0
```

### 1.6 Three properties of this workflow worth knowing

- **A tag uploads to `beta`, never production.** Only `workflow_dispatch` offers a choice, and
  production is not in its dropdown. Promotion to production stays a deliberate Play Console
  action — a sensible default while the account is young.
- **`status: completed`** means the upload goes live on its track immediately, not as a draft.
- **versionCode is `10 + GITHUB_RUN_NUMBER`**, ignoring the literal in `build.gradle.kts`. It must
  always exceed the last code Play accepted. Manual uploads and CI uploads share one increasing
  namespace, so if a manual upload ever outruns `10 + run_number`, CI starts emitting lower codes
  and Play rejects them. Bump the offset in `release.yml` if that happens.

---

## 2. Removing the in-app purchase from Play

The "In-app purchases" badge on a store listing is **not a checkbox**. Play derives it from whether
the app has *active* one-time products. Remove the product and the badge follows.

### 2.1 Check for the product

**Play Console** → app → **Monetise with Play** → **Products** → **One-time products**.
(Google renamed "in-app products" to "one-time products"; older docs and the Billing API still say
in-app products.)

**Checked 2026-09-22: this list was empty — "No results".** `terrawatch_plus` was never created as
a Play product, so there has never been anything to deactivate. That independently confirms, from
Play's own side, the premise the whole ads-only change rests on: the purchase was never sellable,
so there are no purchasers to refund or grandfather. The code-side evidence was a permanently blank
`REVENUECAT_API_KEY`; this is the other half of the proof.

If a product ever does exist here, **deactivate** rather than delete — deactivating preserves
history and is reversible if the parked work on `feat/plus-purchase-parked` is revived.

Also confirm **Subscriptions** is empty.

### 2.2 Update the questionnaires — they do not update themselves

These are declarations you made separately; an empty product list does **not** clear them. If one
still claims the app has purchases, that is the thing actually wrong on the listing.

**Where "App content" lives:** left sidebar, in the **Policy and programmes** group — below
"Monetise with Play", so you will likely need to collapse that and scroll. Direct URL:

```
https://play.google.com/console/u/<n>/developers/<developerId>/app/<appId>/app-content
```

Then:

1. **Data safety** → **Financial info**: confirm purchase history is **not** collected
2. **Content ratings** → reopen the questionnaire → answer **No** to digital purchases → resubmit.
   The rating itself is unlikely to change.
3. **Ads** → must remain **Yes, my app contains ads**

### 2.3 Verify

After the next release propagates, the listing should show **Contains ads** and no **In-app
purchases** badge. Expect up to a day of lag.

---

## 3. Verifying ads actually display

Five layers, cheapest first. Use them in order — each one rules out a different cause.

### Layer 1 — Is the code path working?

Temporarily set `ADMOB_BANNER_UNIT` to Google's test banner unit
`ca-app-pub-3940256099942544/6300978111`, rebuild, and look for the "Test Ad" label. Test units
always fill, so a blank slot here means the *app* is at fault.

**Always restore the real unit afterwards and verify it**, e.g. by inspecting the packaged manifest:

```bash
python3 -c "
import zipfile,re
z=zipfile.ZipFile('composeApp/build/outputs/bundle/release/composeApp-release.aab')
n=[x for x in z.namelist() if x.endswith('AndroidManifest.xml')][0]
print(sorted(set(re.findall(rb'ca-app-pub-\d{16}', z.read(n)))))
"
```

### Layer 2 — What is the SDK saying?

```bash
adb -s <serial> logcat -c && adb -s <serial> logcat | grep -iE "Ads|AdView"
```

`Ad failed to load : N`:

| N | Meaning | Action |
|---|---|---|
| 0 | Internal error | Usually transient; retry |
| 1 | Invalid request | Wrong ad unit ID, or app ID mismatch in the manifest |
| 2 | Network error | Device connectivity |
| 3 | **No fill** | Code is fine. AdMob had nothing to serve — see Layer 3 |

### Layer 3 — Diagnosing no-fill

In rough order of likelihood for a newly live app:

1. **AdMob app not linked to the Play listing.** AdMob → **Apps** → TerraWatch → **App settings** →
   link to the published app. Unlinked apps get poor fill because AdMob cannot verify them.
2. **Young ad unit.** New units commonly serve nothing for hours, occasionally a day or two.
   Recorded previously in commit `a7e910d`.
3. **`app-ads.txt` not served.** `docs/app-ads.txt` must be reachable at the root of the developer
   website declared on the Play listing (`https://<domain>/app-ads.txt`). AdMob → **Apps** →
   **app-ads.txt** shows verification status; unverified suppresses demand.
4. **Debug-signed build.** Re-test from a build signed with the real upload key.

### Layer 4 — Register the device as an AdMob test device

The correct way to exercise the **real** ad unit without generating invalid traffic. Find the device
hash in logcat:

```bash
adb -s <serial> logcat -d | grep -i "RequestConfiguration.Builder"
```

AdMob then serves test creatives through the production unit, proving the whole real path.

### Layer 5 — Production truth: the AdMob console

Only this confirms ads serve to real users. AdMob → **Apps** → TerraWatch:

- **Ad requests** — the app is asking (integration is live)
- **Match rate** — share of requests that got an ad; low match rate is a fill problem, not a code problem
- **Impressions** — ads actually rendered
- **Show rate**

Data lags several hours. It is not real time.

### The one hard rule

**Never tap your own ads** — not even once, not to "check it works". Google's invalid-traffic
detection is aggressive and the penalty is account suspension, which takes the revenue with it.
Test units (Layer 1) and test devices (Layer 4) exist so that you never need to.
