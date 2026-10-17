# Changelog
## Unreleased
- Nearby tab: two live radars side by side, Wi-Fi and Bluetooth, each with its device list underneath (strongest first). Wi-Fi dots sit in 2.4 / 5 / 6 GHz wedges by channel, Bluetooth dots in drone / tracker / other wedges; distance comes from signal strength. A sweep beam lights each dot as it passes and a ring ripples out whenever fresh scan data lands, so you can see the radios are being listened to. Tap a dot or a row to hunt for it. The sample-data Wi-Fi networks now carry channels so the Wi-Fi radar fills in.
- Nearby tab, under the radars: a live **Wi-Fi spectrum** (channel-by-channel crowding with peak hold and the busiest channel in words), a **Bluetooth activity waterfall** (last minute of signal strengths), and **new-arrival call-outs** (a ring and a NEW label when a Wi-Fi network, tracker or drone shows up after you opened the screen).
- **Sweep receipt**: the 30-second sweep now ends with a receipt that appears line by line - what was heard, and what a phone cannot check. The sweep button is also on the Nearby tab.
- Finder: optional **beep guide** that speeds up as you get closer (off by default, hidden in Discreet mode).
## 0.7.0
- RF mic sweep (SDR build): Burst scan finds carriers that come and go (seen in some passes but not most), and Watch logs one frequency's on/off pattern (duty cycle, burst and gap length, repeat period). Not yet tested with real hardware.
- **New application ID `io.github.sloppytopp.n0rma`** (was `...homewatch`). This is a new app to Android: install it, then uninstall the old one; history is not carried over.
- Rebaseline button on the home screen: once everything is checked and all clear, one tap (with a confirmation) locks the Wi-Fi networks, trackers and camera-like sources around you in as safe and clears the open items; from then on any new Wi-Fi network is flagged as a Suspect right away. Reset baseline undoes it.
- Four themes in Settings: Calm (default), Night vision (dim red on black), Executive (slate, one headline) and Classic (the original DEFCON look).
- Calm home screen is now the default: grey/white, system fonts, one Scan button, three cards (Active scans / Unresolved / Evidence), an Info/Suspect/Alert timeline with a per-event "Mark resolved", quick actions and the evidence export. "Watch" is now "Suspect". The old DEFCON strip, Matrix rain and red Night mode are behind Setup -> Hacker theme.
- Optional SDR build (`assembleSdr`, separate app id): Mic tab with an RTL-SDR hidden-microphone sweep (baseline, scan, tone test with controls). The standard build is unchanged and still has no internet permission.
## 0.7.0-beta1 (pre-release)
- Detect: Bluetooth pairing pop-up flood (Flipper Zero / phone-app Bluetooth spam) - behaviour test, not yet verified against real Flipper hardware
- Smart devices tab (nearby gadgets by maker, privacy checklist, DeFlock link); "This is mine" on Status and My devices list; one alert per camera radio
- TSCM tools: physical-inspection checklist per room, camera-lens finder (new optional CAMERA permission, nothing stored or sent), TSCM-style sweep report
- Survey: write-time gate and automatic thinning; fix "app not responding" on long walks
- Following: GPS movement veto (fails open), stale "followed you" card fixed
## 0.6.0
- "Try it with sample data" mode; release signing and shrunken build; F-Droid/store metadata; public docs (privacy, security, contributing)
- Fix: CSV formula injection in the WiGLE export (also 0.5.1)
## 0.5.0
- Survey walks with GPS, local map with estimates (needs a real walk), KML + WiGLE CSV export, WiGLE lookup helper
## 0.4.0
- Discreet mode (generic notifications, PIN, FLAG_SECURE, Notes/Weather disguise, quick exit); room sweeps
## 0.3.x
- Nearby lists, tap-to-find hunts (warmer/colder, compass sweep, drone GPS arrow), liveness banner, welcome, night switch everywhere, "This is mine", real compass dial
## 0.2.0
- Wi-Fi scanning, radar and drone map, home location (GPS / address / manual), history and beep report
## 0.1.0
- Bluetooth scanner: Remote ID drones and separated trackers
