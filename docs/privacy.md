Privacy Policy — TerraWatch
===========================

Last updated: 2026-08-15

TerraWatch ("the app") is a live earthquake monitor for Android. This page explains what data the
app uses, what it does not, and why. It is written to match exactly what the app's code actually
does — not aspirational language.

## Short version

- No account, no sign-in, no user profile of any kind.
- Your home location, if you set one, is stored only on this device. It is never uploaded to
  TerraWatch or to any third party.
- The app fetches public earthquake data from USGS and EMSC. It fetches map tiles from OpenFreeMap
  / OpenStreetMap. It fetches related news headlines from GDELT for major quakes. None of these
  requests carry your location or any other personal identifier — they are the same public data
  requests every user of the app makes.
- The app shows ads via Google AdMob, which collects advertising identifiers as described below.
- No analytics SDK, no crash-reporting SDK, and no tracking beyond what AdMob itself does for ad
  serving.

## Location

TerraWatch can use your device's approximate location (`ACCESS_COARSE_LOCATION`) to set a "home"
point, so the app can tell you what "nearby" means for alerts and for the status pill on the map.
This is entirely optional — during onboarding and in Settings you can instead pick a city from a
list, or skip location entirely and still use every other part of the app (the worldwide
magnitude-6+ alert rule needs no home location at all).

If you do grant location or pick a home city, that coordinate is stored only in the app's local
on-device database. It is never sent over the network, to TerraWatch or to anyone else — including
when the app checks for nearby earthquakes. That check works by fetching the public earthquake
feed (which is the same for every user, everywhere) and comparing it against your stored home
location entirely on your device. Your coordinates never leave your phone.

## Earthquake, map, and news data

TerraWatch's core feature — showing you earthquakes — works by calling public data sources:

- **USGS** (`earthquake.usgs.gov`) — the United States Geological Survey's public earthquake feed
  and archive API.
- **EMSC** (`seismicportal.eu`) — the European-Mediterranean Seismological Centre's public
  real-time earthquake stream.
- **OpenFreeMap / OpenStreetMap** (`tiles.openfreemap.org`) — free, keyless vector map tiles, ©
  OpenStreetMap contributors.
- **GDELT** (`api.gdeltproject.org`) — for magnitude 5.5+ quakes, related public news headlines you
  can choose to open in your browser.

These are ordinary public API requests (the same kind any web browser makes). They do not carry
your location, your name, or any identifier tied to you individually — TerraWatch has no user
accounts to tie a request to in the first place.

## Notifications

TerraWatch can send you digest notifications for earthquakes matching your alert rules — a periodic
check (not instant), under your control. You grant the `POST_NOTIFICATIONS` permission during
onboarding, and you can toggle alerts on or off at any time in Settings. Notifications are
generated entirely on your device; no notification data is sent to a TerraWatch server or any
external service. Your notification history and alert rules are stored only on your device.

## Ads

TerraWatch shows banner ads via Google AdMob to support the free version of the app (ads are never
shown over the map, over the earthquake detail sheet, or during onboarding). AdMob may collect
advertising identifiers and other device information to serve and measure ads, and may share this
data with Google. This is governed by Google's own policies:

- [Google Privacy Policy](https://policies.google.com/privacy)
- [How Google uses information from sites or apps that use Google services](https://policies.google.com/technologies/partner-sites)

### Advertising identifier

TerraWatch's manifest includes `com.google.android.gms.permission.AD_ID` (and
`android.permission.ACCESS_ADSERVICES_AD_ID`), which are merged in transitively by the Google
Mobile Ads SDK. The advertising identifier is collected by Google AdMob whenever an ad is displayed
in the app. This is standard for any Android app carrying Google's ads library and is how ad networks
measure and serve targeted ads. You can reset or opt out of personalized advertising via your
device's advertising settings.

TerraWatch Plus, an optional in-app purchase, removes ads. More Plus features are in development.

## What TerraWatch does not do

- No accounts, no login, no password, no email collection.
- No analytics SDK. No crash-reporting SDK. Nothing about your usage of the app is transmitted to
  TerraWatch's developer or to any analytics service.
- No sharing of your location, or any other data, with other users of the app — TerraWatch has no
  social features at all.
- No sale of personal data, to anyone, ever.

## Children's privacy

TerraWatch is a general-audience informational app about public earthquake data. It does not
knowingly collect personal information from children, and it has no account system that could
collect such information in the first place.

## Data retention and deletion

Everything TerraWatch stores (home location, alert preferences, cached quake data, theme choice)
lives in the app's local storage on your device. Uninstalling the app removes all of it, the same
as any Android app. There is no server-side account or profile to separately request deletion of,
because none exists.

## Changes to this policy

If TerraWatch's data practices change (for example, when a real analytics or crash-reporting SDK
is ever added), this page will be updated and the "Last updated" date above will change.

## Contact

Questions about this policy or the app: please open an issue on this repository.
