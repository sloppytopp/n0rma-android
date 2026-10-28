# Changelog
## Unreleased
- Radar cards say "weak signal: far away or behind walls" for weak readings and show the dBm; the Settings footer shows the real version.
- Magnetic locator (Rooms tab) and a Wi-Fi band view (2.4 / 5 / 6 GHz) for survey walks.
- New optional **Pro build** (`./gradlew assemblePro`, separate app `...n0rma.pro`): saved case files with your notes and location-stripped photos inside the hash chain, a readable PDF shared next to the verifiable .txt, a MAC-hiding toggle, and a timestamp hand-off. Detection stays free in every build.
## 0.7.0
- Home screen now has two live proximity radars (Bluetooth and Wi-Fi) with a plain-language card for each result. Each card has Find it, This is mine, Watch it (alerts when a pinned Wi-Fi device returns) and Details (raw MAC, maker, signal, channel, security). The Unresolved card opens the list of items to review, each with a one-line explanation. Distance is estimated from signal strength; a dot's angle carries no meaning.
- "Walk the whole house" (Rooms tab): a guided, GPS-free walk room by room that reports what is probably inside the home, what is a neighbor's (weak in every room) and what is unclear. Outside networks can be labelled as neighbors'. A network that only copies one of your network names is never hidden as yours.
- Lower idle battery: Bluetooth scans in balanced mode and switches to full power only while hunting, sweeping or inspecting; the ongoing notification updates only when its text changes.
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
