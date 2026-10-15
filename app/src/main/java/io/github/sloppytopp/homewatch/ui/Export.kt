package io.github.sloppytopp.homewatch.ui

import android.content.Context
import android.content.Intent
import io.github.sloppytopp.homewatch.data.Store
import io.github.sloppytopp.homewatch.detect.Evidence
import io.github.sloppytopp.homewatch.detect.Follow

/** Opens the Android share sheet with the tamper-evident evidence report (last 30 days). Nothing is sent unless the user picks a destination. */
fun shareEvidenceReport(ctx: Context) {
    val now = System.currentTimeMillis()
    val rpt = Evidence.build(now, Store.events(now - 30 * 86_400_000L, 1500), Follow.analyze(Store.trail()), Store.trail())
    ctx.startActivity(Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "N0RMA evidence report").putExtra(Intent.EXTRA_TEXT, rpt), "Share report"))
}
