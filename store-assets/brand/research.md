# TerraWatch brand mark — competitive research (Plan 5 Task 4)

Research for a launcher-icon redesign exploration. Every finding below traces to a URL actually
fetched this session (via the Claude Browser pane or WebFetch/WebSearch) — where a fetch failed or
returned nothing useful, that is stated explicitly rather than filled in from memory or guessed.

## What's actually out there

### LastQuake (EMSC-CSEM) — official EMSC app

Fetched: https://play.google.com/store/apps/details?id=org.emsc_csem.lastquake&hl=en_US (Play Store
listing, screenshot + page text both read directly).

Icon: a red rounded-square badge. Bold white stacked wordmark "CSEM" over "EMSC" fills most of the
badge. Behind/through the letters runs a faint dark seismograph waveform line, and the red field
itself carries a faint darker world-continents silhouette texture. So one icon alone stacks three
category clichés at once: alarm red, seismograph waveform, and a world/globe reference — plus an
institutional acronym wordmark, not a pictorial mark. In-app screenshots on the same listing show a
dark-themed list UI with green pill rows (bold magnitude number + place + time-ago + felt-count) —
notable because that's the same "color-coded magnitude badge" convention TerraWatch's own
`MagnitudeBadge.kt` uses, independently arrived at.

Real listing copy (fetched verbatim): "LastQuake is a free, mobile application dedicated to
alerting populations and gathering testimonies in real-time when an earthquake occurs. Designed by
seismologists, LastQuake is the official app of the Euro-Mediterranean Seismological Center (EMSC)."

### MyShake (UC Berkeley Seismological Laboratory / USGS ShakeAlert partner)

Fetched: https://play.google.com/store/apps/details?id=edu.berkeley.bsl.myshake&hl=en_US (Play
Store listing, screenshot read directly; icon also viewed at full 512px via its direct
play-lh.googleusercontent.com asset URL).

Icon: white background, a teal/cyan node-and-line graph mark — a small ringed center dot connected
by thin lines to several smaller satellite dots scattered around it — beside a teal "MyShake"
wordmark. This reads as a sensor-network / mesh motif (fitting: MyShake's actual mechanism is
crowdsourced phone-accelerometer data), not a waveform, not a shield, not a pin. It is the one
researched mark that is NOT red and does NOT use a seismograph spike. Listing hero screenshot shows
a real push notification mockup: "Earthquake Detected, drop, cover, hold on" with three orange
DROP!/COVER!/HOLD ON! panels — high-alarm color in the notification content itself even though the
app icon stays calm.

### Earthquake Network (Futura Innovation SRL)

Fetched: https://play.google.com/store/apps/details?id=com.finazzi.distquake&hl=en_US (listing
screenshot read directly; icon viewed full-size via its googleusercontent asset URL).

Icon: white circle background containing a light-blue wireframe globe (a graticule/grid sphere with
small dot-nodes at the grid intersections) with a bold red ECG/seismograph pulse spike cutting
horizontally straight through the middle. This is the most literal possible combination of two
named clichés — globe grid + red seismograph waveform — in a single mark. The listing's separate
marketing hero art (not the icon itself) shows concentric Wi-Fi-style signal arcs radiating from
phone glyphs near an illustrated collapsing building with a red epicenter dot, reinforcing the
"crowdsourced phone network detects the quake" story visually.

### Volcanoes & Earthquakes (VolcanoDiscovery)

Fetched: https://play.google.com/store/apps/details?id=com.volcanodiscovery.volcanodiscovery&hl=en_US
(listing screenshot + page text both read directly).

Icon: light-blue circle, a black volcano silhouette with a red lava/eruption burst at the summit,
and a white ECG/seismograph waveform line running horizontally through the volcano's midsection —
again a direct literal stacking of two clichés (volcano pictogram + seismograph line) in one mark.

### QuakeFeed

Play Store: searched, and the package id surfaced by search (`com.quakefeed`) was fetched directly
— https://play.google.com/store/apps/details?id=com.quakefeed&hl=en&gl=US returned a Play Store
"Not Found" page (page title "Not Found", body "We're sorry, the requested URL was not found on
this server."), so **QuakeFeed's Android/Play Store presence could not be confirmed this session**.
A separate WebFetch attempt on quakefeed.com itself returned **HTTP 403 Forbidden** — also a failed
fetch, stated here rather than papered over.

What could be confirmed: the iOS App Store listing, fetched and screenshotted directly —
https://apps.apple.com/us/app/quakefeed-earthquake-alerts/id403037266. Icon: an orange/salmon
gradient circle with concentric ripple rings radiating outward (an "epicenter ripple" reading) and
a red seismograph waveform spike cutting through the center — a third mark independently combining
concentric rings and a red waveform. QuakeFeed reads as iOS-primary/exclusive today, not a
confirmed Android competitor.

### USGS branding

Search turned up no dedicated consumer-facing "USGS Earthquakes" app on Google Play — every
earthquake app that surfaced under USGS-adjacent search terms (e.g. "Earthquake Alert!" by Josh
Clemm, "My Earthquake Alerts - Map" by JRustonApps, "Global Earthquake Tracker") is a third-party
app that merely *consumes* USGS's public feed, not an official USGS product. USGS's own
institutional mark — fetched and screenshotted at
https://en.wikipedia.org/wiki/File:USGS_logo_green.svg — is a wordmark: bold green "USGS" lettering
beside a stylized green flag/ribbon shape carrying horizontal wave lines, with the tagline "science
for a changing world" beneath in green. It's a government-agency wordmark, not a pictorial app
icon, and not something to riff on directly — but it confirms that "official" seismic-agency
branding leans on institutional green plus a flag/topographic-line abstraction plus typography,
not illustration or alarm-red.

A related attempt to independently view the ShakeAlert program's own logo
(https://www.usgs.gov/media/images/shakealert-logo-0, navigated and screenshotted) did not return a
usable image in the render this session (page loaded, byline and download buttons visible, but the
image itself did not appear in either screenshot taken, including after a scroll attempt that timed
out) — noted honestly as an inconclusive fetch rather than described from assumption.

### Weather/safety app icon convention (adjacent category)

Fetched: https://play.google.com/store/search?q=weather&c=apps&hl=en_US and
https://play.google.com/store/search?q=earthquake&c=apps&hl=en_US (both screenshotted directly).

Weather row: "Weather & Radar Forecast" (WetterOnline) = dark navy circle, gold/white
sun-and-orbit-ring pictogram; "Weather Radar" (Meteored) = sky-blue rounded square, single bold
white cloud pictogram. Convention: flat or gradient sky-blue/navy field, exactly one bold weather
pictogram, no wordmark inside the icon itself.

Earthquake category row (general search, beyond the 4 named apps above): "My Earthquake Alerts -
Map" (JRustonApps) = red rounded square, white seismograph waveform pulse — a fourth independent
instance of the red-badge-plus-waveform combination, confirming it's the category's dominant
pattern rather than a coincidence across the 3-4 apps above.

## Synthesis

**What's crowded:** alarm red is the category's default badge color (LastQuake, Earthquake
Network's waveform, QuakeFeed, My Earthquake Alerts all use it as the dominant or accent color), and
the literal seismograph/ECG waveform spike is the category's default pictogram — 4 of the 6
marks/apps actually inspected use it, usually as the sole or central device. Globe-grid (Earthquake
Network), volcano silhouettes (VolcanoDiscovery), and concentric ripple rings (QuakeFeed) are the
next tier of recurring devices. Several icons stack two of these clichés into one mark (globe +
waveform; volcano + waveform), which reads busy and — at 48dp launcher size — largely
interchangeable: three of the four waveform-using icons would be hard to tell apart as pure
silhouettes. Notably, the brief's assumption of "red pins" as a cliché did NOT show up in any of the
5 icons actually inspected — map pins appear in marketing screenshots/hero art (e.g. Earthquake
Network's epicenter dot), not in the app icons themselves, so that's a cliché to note as absent
rather than confirmed.

**What's open:** MyShake is the sole researched precedent for a calm, non-alarmist mark in this
exact category — white background, cool teal, an abstract sensor-network motif, no red, no
waveform — and it is also the most institutionally credible entry researched (UC Berkeley / USGS
ShakeAlert partner, 1M+ downloads on the fetched listing), which is real evidence that "calm" is a
viable, credible position here, not just a differentiation gimmick. TerraWatch's own store listing
copy (`store-assets/listing.md`, already in this repo) is explicitly built around the same
positioning — "Monitor" not "Alerts" in the title, "not an early-warning system," "stay informed,
not scared" — so a mark that reads calm/reassuring first and "earthquake app" second, avoiding
red-as-primary and avoiding the literal waveform-spike silhouette used by most of the category,
is genuine whitespace rather than a stretch. The Calm Guardian palette itself is already positioned
here by construction: its one true alarm-intensity red (`MagMajor` `#C43A2F`) is reserved for the
magnitude-severity system, not brand identity, so staying in Safe-green/Water-blue/Ink for the mark
itself is a natural fit, not a compromise.

## Failed/inconclusive fetches (for the record)

- `quakefeed.com` — WebFetch returned HTTP 403 Forbidden.
- `play.google.com/store/apps/details?id=com.quakefeed` — resolved to a real "Not Found" page
  (confirmed, not assumed) rather than a QuakeFeed Android listing.
- `usgs.gov/media/images/shakealert-logo-0` — page loaded but the logo image itself did not render
  in either screenshot taken; a scroll action on that tab also timed out. Left unconfirmed rather
  than guessed at.
