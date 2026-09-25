package cz.loplex.dogvision

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import cz.loplex.dogvision.core.Arrangement
import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.PixelSink
import cz.loplex.dogvision.core.PixelSource
import cz.loplex.dogvision.core.View
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The folder under Pictures that snapshots and converted photos go to. */
const val PICTURES_FOLDER = "Dog vision"

/** Whether saving to Pictures needs the storage permission, as it does before Android 10. */
val savingNeedsPermission: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

/**
 * A name for a snapshot of [view] taken now, as the desktop window names it: dog-cat-20260925-105600.png,
 * with [suffix] before the extension.
 *
 * A photo converted at full size is named so too, with the suffix "-full", not after the photo as
 * the desktop window names it: the system photo picker hides a photo's file name.
 */
fun snapshotName(view: View, suffix: String = "", now: Date = Date()): String {
    val shown = view.compare?.takeIf { view.sideBySide }?.let { "${it.id}-vs-${view.params.species.id}" }
        ?: view.params.species.id
    return "dog-$shown-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(now)}$suffix.png"
}

/** The images of a view put together as it shows them, side by side or one above another. */
fun stitch(images: List<Image>, arrangement: Arrangement): Bitmap {
    val width = images.first().width
    val height = images.first().height
    val bitmap = if (arrangement == Arrangement.ROW) {
        Bitmap.createBitmap(width * images.size, height, Bitmap.Config.ARGB_8888)
    } else {
        Bitmap.createBitmap(width, height * images.size, Bitmap.Config.ARGB_8888)
    }
    images.forEachIndexed { i, image ->
        val (x, y) = if (arrangement == Arrangement.ROW) width * i to 0 else 0 to height * i
        bitmap.setPixels(image.pixels, 0, width, x, y, width, height)
    }
    return bitmap
}

/** A bitmap's rows, for core's pipeline to read. */
class BitmapSource(private val bitmap: Bitmap) : PixelSource {
    override val width = bitmap.width
    override val height = bitmap.height

    override fun readRow(y: Int, into: IntArray) = bitmap.getPixels(into, 0, width, 0, y, width, 1)
}

/** A bitmap for core's pipeline to write rows into. */
class BitmapSink(val bitmap: Bitmap) : PixelSink {
    override fun writeRow(y: Int, x: Int, pixels: IntArray, offset: Int, length: Int) =
        bitmap.setPixels(pixels, offset, length, x, y, length, 1)
}

/**
 * Saves [bitmap] as a PNG called [name] in Pictures/[PICTURES_FOLDER], where the gallery shows it;
 * throws IOException if it cannot be written.
 */
fun savePng(context: Context, bitmap: Bitmap, name: String) {
    if (!savingNeedsPermission) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$PICTURES_FOLDER")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("The gallery refused $name")
        try {
            resolver.openOutputStream(uri)?.use { write(bitmap, it) } ?: throw IOException("Cannot write $name")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (error: IOException) {
            resolver.delete(uri, null, null)
            throw error
        }
        return
    }
    // Deprecated since Android 10, and the only way to Pictures before it.
    val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), PICTURES_FOLDER)
    if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Cannot make $folder")
    val file = File(folder, name)
    file.outputStream().use { write(bitmap, it) }
    MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf("image/png"), null)
}

private fun write(bitmap: Bitmap, out: OutputStream) {
    bitmap.setHasAlpha(false) // the view is opaque, so the PNG needs no alpha channel
    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("Cannot encode the image as PNG")
}
