package io.github.sloppytopp.n0rma.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import io.github.sloppytopp.n0rma.detect.CaseFile
import io.github.sloppytopp.n0rma.detect.CasePhoto
import java.io.File

/** Brings a picked photo into a case: rotated upright, shrunk, and RE-ENCODED as JPEG, which drops all EXIF (including GPS). */
object PhotoImport {
    private const val MAX_EDGE = 2048

    fun strip(ctx: Context, uri: Uri, dest: File): CasePhoto? = try {
        val orientation = ctx.contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            ?: ExifInterface.ORIENTATION_NORMAL
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        var bmp: Bitmap? = null
        bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw IllegalStateException("decode failed")
        val src: Bitmap = bmp
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f); ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f); ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        val scale = MAX_EDGE.toFloat() / maxOf(src.width, src.height)
        if (scale < 1f) m.postScale(scale, scale)
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        dest.parentFile?.mkdirs()
        dest.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        if (out !== src) src.recycle(); out.recycle()
        CasePhoto(dest.name, CaseFile.sha256(dest.readBytes()), System.currentTimeMillis(), "")
    } catch (t: Throwable) { dest.delete(); null }
}
