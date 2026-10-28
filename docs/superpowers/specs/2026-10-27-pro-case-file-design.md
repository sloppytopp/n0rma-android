# N0RMA Pro "Case File" - design spec (draft v1)

Based on a read-only pass over sloppytopp/n0rma-android (v0.7.0, versionCode 9). Nothing in the repo was changed.

## 1. What Pro is

Detection stays free. Pro helps a person convince someone else (landlord, platform, police, advocate) by packaging what the app found into a **case file**: a readable PDF, the person's own photos and notes, and an optional trusted timestamp. It never claims the file will hold up in court or get a result.

## 2. What the code already gives us (and the constraints it sets)

- `detect/Evidence.kt` builds the text report and `verify()` checks it. Every line is SHA-256 chained, the section above the log is covered by the starting hash, and `verify()` requires an **exact footer**. Tests (FollowTest, DetectTest) cover edits, truncation and appended text, and one test verifies a report produced by the Linux tool. The hash-domain string `homewatch-evidence-v1` must never change.
- `Evidence.build(..., extra = ...)` already takes a free-text block that is **covered by the hash chain**. Photo and note sections can go there with no format change.
- The sweep report is the same builder with a different title plus `Tscm.sections(...)`, which already prints "NOT SWEPT" per room and lists what a phone cannot check.
- Reports are shared today as plain text through `Intent.ACTION_SEND` (`ui/Export.kt`, `ui/HistoryScreen.kt`). There is **no FileProvider, no PDF code, no photo code**.
- The standard build has **no INTERNET permission**. Only the `sdr` source set adds it, as a separate app (`applicationIdSuffix ".sdr"`). PRIVACY.md promises "collects nothing and sends nothing."
- `allowBackup="false"` is already set. A PIN vault exists (`data/PinVault.kt`, PBKDF2 at 310,000 iterations, 30 s lockout after 5 misses, wired in through `Prefs.kt`).
- `Store.prune()` deletes history after `KEEP_DAYS` (30 days). PRIVACY.md says so.
- METHODOLOGY.md says detection was tested on one phone (Android 11); Android 12+ is unverified.

## 3. Design

### 3.1 The verified text report stays the source of truth
- Do not touch `Evidence.verify()`, the footer or the version string.
- The PDF is a **rendering** of the text report. Its last pages carry the full report text and the CHAIN END value, plus plain verification steps.
- Share the `.txt` and the `.pdf` together (`ACTION_SEND_MULTIPLE`), so a verifier can use the exact text. PDF text extraction can alter whitespace, so the PDF alone cannot be the thing that gets verified.

### 3.2 Label what the app saw versus what the person said
Two kinds of facts go into one report, and they must never look equal:
- **App-observed** (hash-chained detections): existing event log and tracker trail.
- **Person-supplied** (photos, notes, "found at"): printed under a heading such as "ADDED BY THE PERSON (not verified by the app)".
- Each photo entry records: file hash (SHA-256 of the stored, location-stripped file), the time **the app imported it** (app clock), and the person's own free-text "where/when found". Photo EXIF dates are never presented as fact.
- This block goes in via `extra`, so it is inside the chain.

### 3.3 Case snapshots outlive the 30-day prune
A case file that disappears on day 31 is useless. Add a user-initiated "Save as case" that copies the selected report text, photos and notes into app-private storage, exempt from `prune()`, with an explicit delete button. PRIVACY.md must be updated to say saved cases are the one exception.

### 3.4 Photos
- Add from the system photo picker (no permission needed) or the camera.
- Strip EXIF/location on import by re-encoding (or an open-source EXIF library; check F-Droid's rules) and apply rotation. Hash the stored file.
- Store in app-private storage (backups are already off).
- Offer an explicit per-photo opt-in if the person wants location kept.
- Camera gotcha to verify on a device: the manifest declares CAMERA, and `ACTION_IMAGE_CAPTURE` can throw when a declaring app lacks the grant. Either request the permission first or rely on the picker.

### 3.5 PDF
- Use `android.graphics.pdf.PdfDocument`. No new dependency, which keeps the F-Droid build simple.
- Contents: title, plain-language summary, timeline, person-supplied section with photos, scope section ("what was NOT checked"), monospace report text, verification steps.
- Share through a new FileProvider (cache path, no storage permission).

### 3.6 Trusted timestamp (decision needed)
The standard app cannot go online. Options, in order of preference:
1. **Hand-off, no new permission.** Show the CHAIN END hash with Copy and Share buttons and plain steps to timestamp it using a public service in the browser. The existing advice (send the hash to yourself or an advocate) already fixes time and content.
2. **Separate Pro app variant**, same pattern as `.sdr` (`applicationIdSuffix ".pro"`, its own manifest with INTERNET). It sends **only the hash** to an RFC 3161 timestamp authority on an explicit tap, stores the signed token with the case, and a failed attempt leaves the report valid. PRIVACY.md needs a separate section for that variant.
- Not recommended: adding INTERNET to the F-Droid app. It would break the app's central privacy promise.

### 3.7 Redaction and wording
- Before export: a toggle to omit home position, device names and MAC addresses. Default: omit coordinates.
- Report language stays objective: what radios heard, where and when. No accusations, no names. The existing summary text ("signal evidence, not proof of who placed a device") stays.
- Add a line stating the detection limits already documented (one test phone; blind spots). Reports are handed to third parties, so overstating reliability is the main legal and ethical risk.

### 3.8 Local-safety notes
- The abuser may have access to the phone. Document that saved cases and photos live on the phone, and point to the existing PIN lock. Confirm in the UI code how the PIN is applied before promising anything about it.
- An export can include sensitive data; the redaction toggle defaults to cautious.

### 3.9 Build and money
- F-Droid builds the `release` build type; do **not** change its applicationId (`io.github.sloppytopp.n0rma`, permanent) or add flavor dimensions that would change what their recipe builds.
- Follow the repo's existing pattern: a new build type (`pro`) like `sdr`.
- Tonight's build: Pro features **unlocked** behind a build-time constant. Payment, license checks, a legal entity and liability terms come later.
- Free forever: all detection, the text evidence report, the sweep report, and verification (`Evidence.verify` stays open and documented so anyone can check a report).

## 4. Build order
1. `Evidence` extra-section builder for person-supplied items, plus unit tests proving the chain still verifies and that editing a photo entry breaks it.
2. Case snapshot storage, prune exemption, delete-all, PRIVACY.md update.
3. Photo import (strip, hash, store) and UI.
4. PDF renderer and FileProvider share of `.txt` + `.pdf`.
5. Timestamp hand-off (3.6 option 1) first; the Pro variant later if wanted.
6. Redaction toggle.

## 5. Open questions for the owner
- Timestamp: hand-off only, or a separate `.pro` app with network access?
- Is a saved case allowed to be kept indefinitely, or capped (for example one year)?
- Who pays first: individuals (one-time unlock) or advocates (team license)? The interviews should settle this before pricing.

## 6. Owner decisions (2026-10-27)
1. Timestamp: hand-off only (copy/share the CHAIN END hash plus plain steps). A `.pro` network variant is deferred.
2. Saved cases are kept until the person deletes them; the list shows each case's age and has a delete-all button.
3. First buyer: individuals, one-time unlock. Advocate/team features come later.
Tonight: steps 1-4 and 6, step 5 as hand-off. Pro is a `pro` build type (applicationIdSuffix ".pro"), unlocked by a build-time constant; no payment, license check or network.
