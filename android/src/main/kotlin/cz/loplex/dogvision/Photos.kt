package cz.loplex.dogvision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import cz.loplex.dogvision.render.Frame
import kotlin.math.max
import kotlin.math.roundToInt

/** The longest side a photo is shown at, which keeps the controls quick; saving it keeps its size. */
const val PREVIEW_LONGEST_SIDE = 1280

/** The rotation clockwise and the mirroring after it that an EXIF orientation asks for. */
fun exifTurn(orientation: Int): Pair<Int, Boolean> = when (orientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> 0 to true
    ExifInterface.ORIENTATION_ROTATE_180 -> 180 to false
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180 to true
    ExifInterface.ORIENTATION_TRANSPOSE -> 90 to true
    ExifInterface.ORIENTATION_ROTATE_90 -> 90 to false
    ExifInterface.ORIENTATION_TRANSVERSE -> 270 to true
    ExifInterface.ORIENTATION_ROTATE_270 -> 270 to false
    else -> 0 to false
}

/** The name a document provider gives the file at [uri], or its last path segment. */
fun displayName(context: Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment ?: uri.toString()

/**
 * The photo at [uri] as sRGB, no larger than [longestSide] if it is given, and turned as its EXIF
 * orientation says; null if it is not an image.
 */
fun decodePhoto(context: Context, uri: Uri, longestSide: Int? = null): Pair<Bitmap, Pair<Int, Boolean>>? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    if (longestSide != null) {
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longestSide) sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
    }
    val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
    val scale = if (longestSide == null) 1.0 else longestSide.toDouble() / max(decoded.width, decoded.height)
    val bitmap = if (scale >= 1) {
        decoded
    } else {
        val width = (decoded.width * scale).roundToInt()
        val height = (decoded.height * scale).roundToInt()
        Bitmap.createScaledBitmap(decoded, width, height, true).also { decoded.recycle() }
    }
    val orientation = resolver.openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
    return bitmap to exifTurn(orientation)
}

/** A bitmap's pixels as a frame, turned as [turn] says. */
fun Bitmap.toFrame(turn: Pair<Int, Boolean>): Frame {
    val frame = Frame.allocate(width, height)
    copyPixelsToBuffer(frame.pixels) // ARGB_8888 is R, G, B, A in memory
    frame.rotation = turn.first
    frame.mirrored = turn.second
    return frame
}
