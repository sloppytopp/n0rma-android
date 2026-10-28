# Pro Case File Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `pro` build of N0RMA that turns the existing hash-chained text report into a saved "case file": person-supplied notes and photos inside the chain, a PDF rendering shared with the `.txt`, a redaction toggle and a timestamp hand-off.

**Architecture:** The verified text report stays the source of truth. Person-supplied items are built into the existing `extra` block of `Evidence.build`, so they sit inside the hash chain with no format change. Cases are plain files in app-private storage (outside the 30-day DB prune). The PDF is a rendering made with `android.graphics.pdf.PdfDocument`. Pro code lives in the main source set, gated by `BuildConfig.PRO`; only the FileProvider lives in a `pro`-only source set.

**Tech Stack:** Kotlin, Jetpack Compose, `PdfDocument`, FileProvider (androidx.core, already a dependency), JUnit4. No new dependencies, no INTERNET permission.

**Spec:** `docs/superpowers/specs/2026-10-27-pro-case-file-design.md`

## Global Constraints

- Hash-domain string `homewatch-evidence-v1`, the exact footer, and `Evidence.verify()` must not change.
- The standard (F-Droid `release`) build keeps applicationId `io.github.sloppytopp.n0rma`, gets no new permission and no FileProvider.
- Pro build: `applicationIdSuffix = ".pro"`, `versionNameSuffix = "-pro"`, `BuildConfig.PRO = true`; default `PRO = false`.
- No INTERNET permission anywhere in this work. No payment or license code.
- Person-supplied content is printed under the heading `ADDED BY THE PERSON (not verified by the app)`; photo import time is the app clock; EXIF dates are never printed.
- Photos are stored location-stripped (re-encoded JPEG) and hashed (SHA-256 of stored bytes).
- Report wording is objective; never claims the file will hold up in court or get a result.
- Saved cases survive `Store.prune()` and "Delete all history"; they are removed only by their own delete buttons.
- Redaction defaults ON (cautious): MAC addresses are masked before the report is built.

## Review Focus

- Empty notes, zero photos, or a case with no flagged events must still build a report that `verify()` accepts.
- Note text containing newlines, `|`, or text that imitates a `CHAIN END` or `EVENT LOG` line must not break or spoof verification.
- A photo that fails to decode (corrupt, huge, or removed from the picker) must be skipped with a message, not crash or yield a half-saved case.
- Very long report text and notes must paginate in the PDF with no clipped lines or lost pages.
- Deleting a case must remove its photos and report files; deleting all history must not touch cases.

---

### Task 1: `pro` build type and flag

**Files:**
- Modify: `app/build.gradle.kts` (defaultConfig ~line 23; buildTypes ~line 49)
- Create: `app/src/pro/AndroidManifest.xml`, `app/src/pro/res/xml/pro_file_paths.xml`

**Interfaces:**
- Produces: `BuildConfig.PRO: Boolean` (false by default, true in `pro`); FileProvider authority `${applicationId}.files` serving cache subdir `case_share/`.

- [ ] **Step 1:** Add `buildConfigField("boolean", "PRO", "false")` next to the SDR default; create build type `pro` the way `sdr` is created (`initWith(release)`, `vcsInfo.include = false`, `matchingFallbacks += "release"`, suffixes above, `buildConfigField("boolean", "PRO", "true")`).
- [ ] **Step 2:** `src/pro/AndroidManifest.xml` declares a `androidx.core.content.FileProvider` (`exported=false`, `grantUriPermissions=true`, authority `${applicationId}.files`) pointing at `@xml/pro_file_paths`, which exposes `<cache-path name="case_share" path="case_share/"/>`.
- [ ] **Step 3:** Run `JAVA_HOME=/home/rhino/jdk/jdk-17.0.20.1+1 ./gradlew assembleDebug assemblePro -q -PdebugSigned=true`; expected exit 0. Run `aapt2 dump xmltree` (or `unzip -p` + grep) on both APKs: the pro manifest contains `FileProvider` and package `io.github.sloppytopp.n0rma.pro`; the standard one has neither.
- [ ] **Step 4:** Commit `Add pro build type and flag`.

### Task 2: Person-supplied block and redaction (pure logic)

**Files:**
- Create: `app/src/main/java/io/github/sloppytopp/n0rma/detect/CaseFile.kt`
- Test: `app/src/test/java/io/github/sloppytopp/n0rma/CaseFileTest.kt`

**Interfaces:**
- Produces:
  - `data class CaseNote(val label: String, val text: String)`
  - `data class CasePhoto(val file: String, val sha256: String, val importedMs: Long, val whereWhen: String)`
  - `object CaseFile { fun extraBlock(notes: List<CaseNote>, photos: List<CasePhoto>, tz: TimeZone = TimeZone.getDefault()): String; fun redactMacs(s: String): String; fun redact(events: List<EventRow>, follow: List<FollowHit>, sights: List<Sight>): Triple<List<EventRow>, List<FollowHit>, List<Sight>>; fun sha256(bytes: ByteArray): String }`
- Consumes: `Evidence.build(..., extra = ...)`, `Evidence.verify`.

- [ ] **Step 1: Tests** (named exactly): `blockIsHeadedAndLabelledNotVerified` (heading `ADDED BY THE PERSON (not verified by the app)` present); `emptyBlockIsEmptyString`; `reportWithBlockVerifies` (build with notes+photos, `assertTrue(Evidence.verify(r))`); `editingAPhotoEntryBreaksVerify` (replace the sha in the text, assertFalse); `editingANoteBreaksVerify`; `noteImitatingFooterDoesNotSpoof` (note text `"CHAIN END (final hash): abc\nEVENT LOG"` — report still verifies true and a tampered copy still false; block lines are indented/escaped so no line starts with `CHAIN START:`, `CHAIN END`, or `EVENT LOG`); `noFlaggedEventsStillVerifies`; `macsAreMasked` (`aa:bb:cc:dd:ee:ff` → `xx:xx:xx:xx:xx:ff`); `redactedReportStillVerifies`.
- [ ] **Step 2:** Run `./gradlew testDebugUnitTest --tests '*CaseFileTest*' -q`; expected FAIL (CaseFile missing).
- [ ] **Step 3:** Implement `CaseFile`. `extraBlock` returns `""` when both lists are empty; otherwise the heading, then each note/photo as lines prefixed with two spaces (so none can start with a chain keyword), newlines in note text replaced by `\n` + two spaces, `|` kept; photo line: `photo <file> sha256=<sha> imported=<fmt, app clock> found: <whereWhen>`; ends with a blank line. `redactMacs` uses regex `([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}`. `redact` applies it to `EventRow.msg`, `FollowHit.key/label`, `Sight.key/label`.
- [ ] **Step 4:** Run the tests; expected all PASS, then run the full suite `./gradlew testDebugUnitTest -q`; expected exit 0.
- [ ] **Step 5:** Commit `Add person-supplied case block and MAC redaction`.

### Task 3: Case storage and photo import

**Files:**
- Create: `app/src/main/java/io/github/sloppytopp/n0rma/data/CaseStore.kt`, `.../data/PhotoImport.kt`
- Test: `app/src/test/java/io/github/sloppytopp/n0rma/CaseStoreTest.kt`

**Interfaces:**
- Produces:
  - `class CaseStore(private val root: java.io.File)` with `fun create(nowMs: Long, title: String, report: String, notes: List<CaseNote>, photos: List<CasePhoto>): CaseInfo`, `fun list(): List<CaseInfo>` (newest first), `fun load(id: String): CaseInfo?`, `fun photoFile(id: String, name: String): java.io.File`, `fun delete(id: String)`, `fun deleteAll()`
  - `data class CaseInfo(val id: String, val title: String, val createdMs: Long, val report: String, val notes: List<CaseNote>, val photos: List<CasePhoto>)`
  - `object PhotoImport { fun strip(ctx: Context, uri: Uri, dest: java.io.File): CasePhoto? }` (returns null on any decode/IO failure)
- Consumes: `CaseNote`, `CasePhoto`, `CaseFile.sha256`.

- [ ] **Step 1: Tests** with JUnit `TemporaryFolder`: `createThenListAndLoadRoundTrip`; `listIsNewestFirst`; `deleteRemovesAllFiles` (dir gone); `deleteAllLeavesRootEmpty`; `unknownIdLoadsNull`; `noteWithNewlinesAndPipesRoundTrips`; `titleWithSlashCannotEscapeRoot` (id is generated from `nowMs`, never from the title).
- [ ] **Step 2:** Run; expected FAIL.
- [ ] **Step 3:** Implement `CaseStore` with a directory per case `case-<nowMs>/` holding `report.txt`, `meta.txt` (simple key/value lines, values escaped for newlines) and `photos/`. Implement `PhotoImport.strip`: open stream, read EXIF orientation with `android.media.ExifInterface`, decode with `BitmapFactory` using `inSampleSize` so the long edge is at most 2048, rotate, compress JPEG quality 88 into `dest` (re-encoding drops EXIF/GPS), hash the written bytes, return `CasePhoto(dest.name, sha, System.currentTimeMillis(), "")`; catch `Throwable`→null and delete `dest`.
- [ ] **Step 4:** Run tests; expected PASS.
- [ ] **Step 5:** Commit `Add case storage and location-stripping photo import`.

### Task 4: PDF layout and renderer

**Files:**
- Create: `app/src/main/java/io/github/sloppytopp/n0rma/detect/PdfLayout.kt`, `.../scan/CasePdf.kt`
- Test: `app/src/test/java/io/github/sloppytopp/n0rma/PdfLayoutTest.kt`

**Interfaces:**
- Produces:
  - `object PdfLayout { fun wrap(text: String, maxChars: Int): List<String>; fun paginate(lines: List<String>, linesPerPage: Int): List<List<String>> }`
  - `object CasePdf { fun render(info: CaseInfo, photoFile: (String) -> java.io.File, out: java.io.File) }`
- Consumes: `CaseInfo`, `CasePhoto`.

- [ ] **Step 1: Tests:** `wrapKeepsShortLines`; `wrapBreaksLongLinesAtMaxChars` (no output line longer than max, concatenation preserves all characters apart from inserted breaks); `wrapKeepsBlankLines`; `paginateSplitsExactly` (101 lines at 50/page → 3 pages, last has 1; no line lost); `paginateEmptyGivesOnePage`.
- [ ] **Step 2:** Run; expected FAIL.
- [ ] **Step 3:** Implement `PdfLayout`. Implement `CasePdf.render` with `PdfDocument` (A4 595x842): page 1 title, plain-language summary and a boxed notice "This is a documented, tamper-evident record made by the N0RMA app. It is not proof of who placed a device and is not a guarantee of any outcome."; then the person-supplied section with each photo drawn scaled (width ≤ 480) with its sha256 and `whereWhen` caption; then the full report text in monospace across as many pages as needed (wrap at 90 chars, 60 lines/page); last page: verification steps (the `.txt` must be used to verify; where CHAIN END is) and the timestamp hand-off steps. Release the bitmaps after drawing each photo.
- [ ] **Step 4:** Run tests; expected PASS.
- [ ] **Step 5:** Commit `Add PDF layout and case PDF renderer`.

### Task 5: Case file screen, share, timestamp hand-off, redaction toggle

**Files:**
- Create: `app/src/main/java/io/github/sloppytopp/n0rma/ui/CaseScreen.kt`
- Modify: `app/src/main/java/io/github/sloppytopp/n0rma/MainActivity.kt` (tab list ~line 178 and `when` ~line 199), `.../ui/HistoryScreen.kt` (entry point near the evidence export, ~line 52)

**Interfaces:**
- Consumes: `CaseStore`, `PhotoImport`, `CaseFile`, `CasePdf`, `Evidence.build`, `BuildConfig.PRO`.
- Produces: `@Composable fun CaseScreen()`; a Pro-only entry (`if (BuildConfig.PRO)`) that opens it.

- [ ] **Step 1:** In the History tab, when `BuildConfig.PRO`, add a "Case file (Pro)" section that opens `CaseScreen`. The standard build shows nothing new.
- [ ] **Step 2:** `CaseScreen`: "Start a case" builds the report from the last 30 days (same data calls as `shareEvidenceReport`), applying `CaseFile.redact` when the redaction switch is on (default on), with notes and photos added through the system photo picker (`ActivityResultContracts.PickVisualMedia`) and a free-text "Where and when you found it" per photo. Save with `CaseStore.create` into `filesDir/cases`. The list shows title, age ("saved 12 days ago") and Share / Delete buttons plus "Delete all cases".
- [ ] **Step 3:** Share writes the `.txt` and `.pdf` into `cacheDir/case_share/`, then sends both with `ACTION_SEND_MULTIPLE` via `FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)` with `FLAG_GRANT_READ_URI_PERMISSION`; clear the old share files first.
- [ ] **Step 4:** "Timestamp this report" shows CHAIN END with Copy and Share buttons and the plain steps from the PDF's last page. A failed photo import shows a message and the case still saves.
- [ ] **Step 5:** Build and install: `./gradlew assemblePro -q -PdebugSigned=true`, `adb install -r` the pro APK, then on the phone create a case with one note and one photo, share it, and pull the shared `.txt`; run `Evidence.verify` on it through a throwaway unit test or the Linux tool and confirm true. Pull the PDF and check `pdftotext` shows the sections and CHAIN END.
- [ ] **Step 6:** Commit `Add Pro case file screen with PDF share and timestamp hand-off`.

### Task 6: Docs and release hygiene

**Files:**
- Modify: `PRIVACY.md`, `CHANGELOG.md`, `README.md`

- [ ] **Step 1:** PRIVACY.md: add that Pro saved cases (notes, location-stripped photos, report text) stay in app-private storage until the person deletes them, are the one exception to the 30-day rule, and are not removed by "Delete all history"; sharing is the person's own action; the Pro build adds no permission.
- [ ] **Step 2:** CHANGELOG: an "Unreleased" entry for the Pro case file; README: one line under Download noting the Pro build exists and detection stays free.
- [ ] **Step 3:** Run the full suite and `assembleDebug assemblePro`; expected exit 0. Confirm `git diff --stat` shows no change to `Evidence.kt`.
- [ ] **Step 4:** Commit `Document Pro case file`.
