# TerraWatch — social launch kit: copy (Plan 5 Task 6, phase 1)

Ready-to-post copy for every platform, drafted against this repo's real shipped state as of
`feat/plan-5-polish` (49cb9f2) — not written from memory. **Nothing in this file has been posted
anywhere and no account has been created.** Account creation is explicitly the user's own action
(this plan's own User-gates line: "social account creation (T6 produces assets only)") — this
document produces the words only; a human copies them in once each account exists. LOGO GATE also
still open (`store-assets/brand/` — direction A/B/C, user pick pending), so every bio/caption below
is written to be logo-agnostic; final avatar/banner art is `art-specs.md`'s job, phase 2.

**Timing note, so nothing here gets posted early by mistake**: bios/profile fields (YouTube About,
IG/Threads bio, X bio) are safe to set up as soon as each account exists — they describe the app
itself, not its store status. The **launch announcement, the X pinned thread, and the 3 IG/Threads
launch posts all say or imply the app is available to install** ("Get it," "free on Android," link
slots) — hold those until the Play Store listing is actually live (Plan 4 Task 8, currently blocked
on RevenueCat/AdMob keys per `docs/ACCOUNT-SETUP.md`), not before. Posting "get it on Android" while
the app isn't installable anywhere yet would itself be the kind of overclaim this whole kit is trying
to avoid.

## Ground truth checked before writing

Every feature claim below traces to one of: `store-assets/listing.md` (the already-vetted, real-code-checked
Play Store copy), the shipped source itself, or this branch's own progress ledger
(`.superpowers/sdd/2026-08-15-terrawatch-plan-5-polish/progress.md`). Spot-checked directly rather than
paraphrased from the plan brief:
- Digest cadence is a real 45-minute `PeriodicWorkRequest`
  (`composeApp/src/androidMain/kotlin/com/yugma/terrawatch/alerts/AlertDigestScheduler.android.kt:73`),
  inside spec §6.5's own "every 30-60 min" band — copy below says "periodically," never a specific
  number, so it stays true even if the cadence is retuned later.
- "Digest, not early-warning" is the app's own internal vocabulary, not marketing spin invented here —
  grepped verbatim in shipped code/tests: `DigestNotificationCopyTest.kt`'s own kdoc ("digest honesty
  (spec §6.5)"), `SettingsScreen.kt`'s real off-state copy ("earthquake digests can't be delivered"),
  and spec §6.5 itself.
- Data sources: `SettingsScreen.kt:723-724` ("Data sources: USGS · EMSC", "© OpenStreetMap contributors"),
  `QuakeMap.android.kt:67` (OpenFreeMap/OpenMapTiles). README confirms "Free APIs, no keys" — no
  backend server exists. Every source named below is one of these three; nothing else is claimed.
  (GDELT news EXISTS in code but is disabled via `NewsFeature.ENABLED = false` since 2026-08-16 —
  never mention news in public copy while the flag is off.)
- Favorites + per-place alert types (Task 2) and my-location FAB + cold-start centering (Task 1)
  are real, CODE COMPLETE on this branch (device-verified in the round-2 pass — copy below
  describes what the app *does*).
- **Plus/monetization**: `PaywallScreen.kt`'s real `PLUS_BENEFITS` = "Remove ads", "Unlimited favorite
  places", "Custom alert rules (coming soon)" — but the buy button is still a disabled stub (no
  RevenueCat key yet; `store-assets/listing.md`'s own content-rating table: "No purchases are live
  today"). Copy below never says "buy Plus" or implies a working purchase flow — at most it repeats
  `listing.md`'s own already-shipped-and-vetted framing ("free, ad-supported; Plus removes ads, more
  features in development") in the one or two spots that need fuller context, and skips monetization
  entirely in the terse punchy pieces (tweets, IG captions) where it would just crowd out the actual
  hook.
- No fabricated download counts, testimonials, user counts, or star ratings anywhere below — the app
  hasn't shipped to a store yet (Task 8 of Plan 4, pending keys).

## Handle research (evidence-based, per platform)

Task brief: check `@terrawatchapp` (+fallbacks `@terrawatch_app`, `@get_terrawatch`) on X, Instagram,
Threads, YouTube via WebFetch of the profile URLs. Results below are exactly what each fetch returned
this session — where a platform blocks anonymous fetches, that's recorded as such, not papered over
with a guess.

| Platform | URL fetched | Result | Signal |
|---|---|---|---|
| **YouTube** | `youtube.com/@terrawatchapp` | Clean **HTTP 404** | Real "handle not claimed" signal — YouTube returns a genuine 404 for an unregistered `@handle`, distinct from a blocked/JS-shell response. **HIGH confidence: available.** |
| **YouTube** (fallback 1) | `youtube.com/@terrawatch_app` | Clean **HTTP 404** | Same signal, also available. |
| **YouTube** (fallback 2) | `youtube.com/@get_terrawatch` | Clean **HTTP 404** | Same signal, also available. |
| **X** | `x.com/terrawatchapp` | **HTTP 402 Payment Required** | X's own anti-scraping wall — returned for the plain profile URL and again after `twitter.com/terrawatchapp` 301-redirected to the same `x.com` URL. Not a "taken" or "available" signal either way — **blocked/inconclusive.** |
| **Instagram** | `instagram.com/terrawatchapp` | Generic logged-out shell (Instagram wordmark + two placeholder images, no bio/post/follower data, no error text) | Instagram serves this identical shell to *every* unauthenticated profile request regardless of whether the account exists — **blocked/inconclusive.** Same result reproduced on the `terrawatch_app` fallback, confirming it's a blanket block, not account-specific. |
| **Threads** | `threads.net/@terrawatchapp` | Fetch failed outright ("unable to fetch from www.threads.net") | Domain-level fetch failure, no content returned — **blocked/inconclusive.** |
| **Threads** (alt domain) | `threads.com/@terrawatchapp` | Generic logged-out shell ("Threads • Log in", footer links only, no profile content) | Same blanket-block pattern as Instagram (Threads identity rides on the Instagram/Meta account graph) — **blocked/inconclusive.** |

**Secondary check** — `WebSearch` for the literal string `"terrawatchapp"` surfaced no X/Instagram/Threads/YouTube
account by that exact handle anywhere on the indexed web. It did surface a real, unrelated prior-art
brand worth flagging: **"TerraWatch Space"** (`x.com/terrawatchspace`, `terrawatchspace.com`, an earth-observation/satellite-data
advisory newsletter+podcast — different niche, different handle string, not a collision but close enough
in name that it's worth knowing about). Also surfaced: an existing unrelated Google Play app literally
named **"Terra Watch"** (`app01.terra.watch`) that also tracks earthquakes — a real naming precedent in
the *same category*, though a different exact app name (ours is "TerraWatch: Earthquake Monitor",
already differentiated) and not a handle collision on any of the 4 social platforms checked.

**Recommendation: `@terrawatchapp` on all four platforms**, in this order of confidence:
- **YouTube: use it, high confidence** — independently confirmed available (clean 404), and also the
  platform where a mismatched handle is most visible/permanent (channel URL), so it's the one worth
  being surest about before committing.
- **X / Instagram / Threads: use it as the first attempt, but confirm live at signup time** — no
  fetch found any evidence it's taken (no search hit, no readable profile content anywhere), but none of
  the three could be *confirmed* available either, since all three block anonymous profile fetches
  uniformly (this is a platform-wide bot defense, not a signal about this specific handle). This is a
  30-second check once the user is logged in and creating the account — the recommendation here is
  "try this first," not "this is guaranteed open."
- **If `@terrawatchapp` is taken on any platform at actual signup**: fall back to `@terrawatch_app`,
  then `@get_terrawatch`, in that order (matching the brief's own fallback order) — both are at least
  confirmed available on YouTube and neither surfaced in the web search, so both are reasonable second
  and third choices, with the same "confirm live at signup" caveat on X/IG/Threads.
- Keep the **same handle across all four platforms** even though only YouTube is independently confirmed
  — cross-platform consistency is worth more than a hypothetical mismatch, and nothing found this
  session suggests `@terrawatchapp` is unavailable anywhere.

---

## YouTube

**Channel name:** TerraWatch
**Handle:** `@terrawatchapp`

**Channel description ("About" tab):**

```
TerraWatch shows you real earthquakes happening around the world, right now — pulled live from
USGS and EMSC, the same public seismic feeds agencies and researchers rely on.

This channel carries the app walkthrough, feature breakdowns, and build-in-public notes from
developing TerraWatch for RevenueCat's Shipaton 2026.

TerraWatch is a digest, not an early-warning system: it checks in periodically and reports what
public seismic agencies have already recorded. It does not predict earthquakes and is not a
substitute for official emergency alerts or guidance from your local authorities.

The map opens right where you are, and you can save favorite places beyond home — each with its
own alert rule (every quake nearby, majors only, or off).

Free on Android, ad-supported (never over the map, never during onboarding). No account required —
your home location stays on your device, always.

Get the app: [PLAY_STORE_LINK]
Source + more: https://github.com/shubham/terrawatch
```

**Demo video — title:**

```
TerraWatch: Live Earthquake Monitor for Android — Full App Walkthrough
```

(70 characters — well inside YouTube's 100-char title cap.)

**Demo video — description:**

```
TerraWatch shows real earthquakes happening around the world right now — pulled live from USGS
and EMSC, the same public seismic feeds agencies and researchers use.

This walkthrough:
0:00 Cold open — live global map, magnitude-colored pins
0:XX Tap a quake — full detail sheet (magnitude, depth, distance, revisions)
0:XX Set your radius — the ring that defines "nearby"
0:XX A digest alert arriving (honest framing: not early-warning)
0:XX History archive — filter by magnitude and year
0:XX Insights — trends, daily counts, strongest quake
(Timestamps approximate — filled in once the recording is actually cut; see this task's own
"video pending device" note.)

TerraWatch is a digest, not an early-warning system: it checks in periodically and reports what
public seismic agencies have already recorded. It does not predict earthquakes and is not a
substitute for official emergency alerts, seismic early-warning systems, or guidance from your
local authorities.

Free on Android, ad-supported (never over the map, never during onboarding). No account required —
your home location stays on your device.

Built with Kotlin Multiplatform + Compose Multiplatform. My entry for RevenueCat's Shipaton 2026.

Get the app: [PLAY_STORE_LINK]
Source + more: https://github.com/shubham/terrawatch

#TerraWatch #Earthquake #Shipaton2026 #KotlinMultiplatform #BuildInPublic
```

**Demo video — tags** (comma-separated, upload field):

```
earthquake, earthquake app, earthquake tracker, earthquake monitor, earthquake alert, live
earthquake map, USGS, EMSC, seismic activity, earthquake today, Android app, Kotlin Multiplatform,
Compose Multiplatform, RevenueCat Shipaton, Shipaton 2026, build in public, indie app developer,
solo developer app, earthquake history, earthquake insights, digest notification, earthquake near me
```

---

## Instagram / Threads

Threads reuses the Instagram account/identity per the plan brief — one bio, one avatar, shared below.

**Bio** (147 characters, ≤150 limit):

```
Live earthquakes worldwide + honest digest alerts. Free on Android. Not early-warning — just the
facts, calmly. Built in public for #Shipaton2026 🌍
```

**Launch post 1 — the map** (image: `store-assets/screenshots-framed/01-home-map.png`, "Live quakes worldwide"):

```
Every earthquake happening right now, worldwide, on one live map. 🌍 Color-coded by magnitude,
updating as new reports come in — straight from USGS and EMSC, the same public feeds researchers
use. No account. No guessing. Just what's actually happening on the ground.

TerraWatch is free on Android — link in bio.
```
Hashtags: `#TerraWatch #Earthquake #EarthquakeApp #USGS #LiveMap #Android #KotlinMultiplatform #Shipaton2026 #BuildInPublic #IndieApp`

**Launch post 2 — the alert** (image: `store-assets/screenshots-framed/06-notification.png`, "Honest alerts"):

```
Our one rule: no fear-mongering. TerraWatch's alerts are a digest, not an early-warning system —
it checks in periodically and tells you what's already been recorded nearby (or anywhere, M6+).
No promise of advance notice, no siren-chasing. Just honest, calm information, when there's
something worth knowing.

Free on Android — link in bio.
```
Hashtags: `#TerraWatch #EarthquakeAlert #EarthquakeApp #USGS #EMSC #Android #Shipaton2026 #BuildInPublic`

**Launch post 3 — the radius** (image: `store-assets/screenshots-framed/04-settings-radius-ring.png`, "Nearby, defined by you"):

```
"Nearby" shouldn't be a black box. TerraWatch draws the exact ring your alerts are based on — pick
your radius (50–1000km), watch it live on the map, and know precisely what "nothing nearby"
actually means.

Free on Android — link in bio.
```
Hashtags: `#TerraWatch #EarthquakeApp #Android #MapDesign #KotlinMultiplatform #Shipaton2026 #BuildInPublic #IndieDev`

**Hashtag sets** (rotate/mix rather than repeating one block every time):
- **Core (use on every post):** `#TerraWatch #EarthquakeApp #Shipaton2026 #BuildInPublic`
- **Feature-specific:** already embedded per post above (map/alerts/radius)
- **Reach pool (rotate a few in, don't dump all at once):** `#Android #KotlinMultiplatform #ComposeMultiplatform #IndieDev #IndieApp #AndroidApp #AppLaunch #Earthquake #Seismology #USGS #EMSC #BuildInPublic`

---

## X (Twitter)

**Handle:** `@terrawatchapp`

**Bio** (147 characters, ≤160 limit):

```
Live earthquakes worldwide, honest digest alerts (not early-warning), full history + trends. Free
on Android. Building in public for #Shipaton2026.
```

**Pinned launch thread** (5 tweets — story: why built → free stack → KMP → honest-alerts philosophy → Shipaton entry + link):

> **1/5**
> Earthquakes happen with zero warning. I kept flipping between five different apps and news tabs
> just to know if the ground had moved somewhere I cared about — so I built the one I wanted:
> TerraWatch. Live global quakes, no drama. 🧵👇
> *(232 chars)*

> **2/5**
> Everything TerraWatch shows comes from free, public feeds — USGS realtime + FDSN archive, EMSC's
> live WebSocket, OpenFreeMap/OSM for the map. No API keys, no backend server, no bill. The same
> data seismologists already use.
> *(222 chars)*

> **3/5**
> Under the hood: Kotlin Multiplatform + Compose Multiplatform. One codebase, shared logic
> (network/db/alert rules) fully unit-tested off the UI thread — Android is the real-time target.
> Entering Shipaton 2026's Ship Kotlin Everywhere award.
> *(239 chars)*

> **4/5**
> The one rule I wouldn't break: TerraWatch is a digest, not an early-warning system. It checks in
> periodically and tells you what's already been recorded — never a promise of advance notice
> before shaking arrives. Real early warning is your local authority's job, not an app's.
> *(276 chars)*

> **5/5**
> Built solo, with an AI pair, in the open — my entry for @RevenueCat's #Shipaton2026. Free,
> ad-supported, no account needed. Try it: [PLAY_STORE_LINK] · More on the build:
> [GITHUB_PAGES_LINK] #BuildInPublic #KotlinMultiplatform
> *(226 chars, before link placeholders resolve to real/shortened URLs — will be shorter in practice)*

All 5 verified ≤280 chars with Python `len()` (not eyeballed) before finalizing here; tweet 4 is the
tightest at 276.

**Image slots + alt text** (only tweets 1, 4, 5 carry an image — 2 and 3 are text-only, which is normal
for an X thread):

| Tweet | Image | Alt text |
|---|---|---|
| 1/5 | `store-assets/screenshots-framed/01-home-map.png` | "Framed screenshot of TerraWatch's home screen: a world map with color-coded earthquake pins, a calm status pill reading no nearby activity, and a live feed list of recent quakes below." |
| 4/5 | `store-assets/screenshots-framed/06-notification.png` | "Framed screenshot of an expanded Android notification titled 'M6.2 near you · 2 h ago' listing real magnitude-6+ earthquakes, with text noting it matches the user's worldwide M6+ alert rule." |
| 5/5 | `store-assets/screenshots-framed/04-settings-radius-ring.png` | "Framed screenshot of TerraWatch's home map showing a translucent green ring around a home location marking a 500 km alert radius, with a status pill confirming nothing is within that radius right now." |

---

## Cross-platform

**1-liner app description** (127 characters — for anywhere only one short line fits: link previews,
Devpost tagline, etc.):

```
Real earthquakes worldwide, right now — with honest digest alerts, a full history archive, and
trend insights. Free on Android.
```

**Launch announcement** (long-form — LinkedIn, Reddit, Shipaton's own Discord `#post-engagement-boost`
channel, or anywhere a fuller post fits):

```
TerraWatch is live — a free Android app that shows real earthquakes happening around the world
right now, pulled live from USGS and EMSC.

It's a digest, not an early-warning system: no false promises of advance notice, just an honest
periodic check-in on what's already been recorded near you (or anywhere, M6+). A full history
archive, trend insights, and a radius ring that shows exactly what "nearby" means for your alerts
round it out — no account required, and your location never leaves your device.

The map opens right where you are, and you can save favorite places beyond home — each with its
own alert rule (every quake nearby, majors only, or off).

TerraWatch is free, supported by ads shown between screens — never over the map, never during
onboarding. TerraWatch Plus removes ads; more Plus features are in development.

Built solo, with an AI pair, in the open, as my entry for RevenueCat's Shipaton 2026 (#BuildInPublic).

Get it: [PLAY_STORE_LINK]
More on the build: [GITHUB_PAGES_LINK]
```

**Link-in-bio structure**

Every platform above gives exactly one clickable bio link. Recommended target: a single page at the
GitHub Pages URL already planned in `docs/ACCOUNT-SETUP.md` (step 7) —

```
https://shubham.github.io/terrawatch/
```

**Not live yet** — Pages hasn't been enabled (`ACCOUNT-SETUP.md`'s own "Tell me when you want it
enabled" is still open, per its Quick Reference item 5). Until it exists, use this **confirmed-live
interim link** instead: `https://github.com/shubham/terrawatch` (verified public today via `gh repo
view` — real, working, and its own repo description already says "Shipaton 2026," so it reads as
intentional, not a placeholder link).

Once Pages is enabled, the page itself should carry (so building it is mechanical, not a fresh design
exercise):
1. **Hero** — winner logo mark (once picked) + app name + the 1-liner above + a "Get it on Google
   Play" badge (links to the Play listing once live).
2. **Feature highlights** (4 blocks) — reuse the 4 already-captioned framed screenshots:
   `01-home-map.png` (live map), `06-notification.png` (honest alerts), `04-settings-radius-ring.png`
   (your radius), `03-insights.png` or `05-history.png` (trends/history — pick whichever reads
   better once laid out).
3. **"Honest by design"** callout — reuse `store-assets/listing.md`'s own "HONEST BY DESIGN" paragraph
   verbatim (already-vetted copy, no need to rewrite it a second time).
4. **Privacy policy** section/link — same Pages site hosts `/privacy` per `ACCOUNT-SETUP.md` step 7.
5. **Shipaton 2026** badge/mention, linking to the Devpost submission once it exists.
6. **Footer** — social icons for all 4 handles above, plus the GitHub repo link (source-available,
   matches the "build in public" framing used throughout this kit).

`app-ads.txt` also lives at this site's root per `ACCOUNT-SETUP.md` step 2.5 — invisible/technical,
not part of the visible page, just needs to exist there once the AdMob publisher ID arrives.
