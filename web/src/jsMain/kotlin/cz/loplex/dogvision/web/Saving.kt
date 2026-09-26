package cz.loplex.dogvision.web

import cz.loplex.dogvision.core.Arrangement
import cz.loplex.dogvision.core.ClockTime
import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.blue
import cz.loplex.dogvision.core.green
import cz.loplex.dogvision.core.red
import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.set
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import kotlin.js.Date

/**
 * How long a download's object URL is kept: a browser may still be reading it after the click, and revoking it at once
 * can cancel the download.
 */
private const val DOWNLOAD_URL_LIFETIME_MS = 60_000

/** The time on the local clock now, which names what is saved. */
fun now(): ClockTime = Date().run {
    ClockTime(getFullYear(), getMonth() + 1, getDate(), getHours(), getMinutes(), getSeconds())
}

/** The images of a view put together as it shows them, side by side or one above another, as the app's stitch does. */
fun stitch(images: List<Image>, arrangement: Arrangement): HTMLCanvasElement {
    val width = images.first().width
    val height = images.first().height
    val canvas = document.createElement("canvas") as HTMLCanvasElement
    canvas.width = if (arrangement == Arrangement.ROW) width * images.size else width
    canvas.height = if (arrangement == Arrangement.ROW) height else height * images.size
    val context = canvas.getContext("2d").unsafeCast<CanvasRenderingContext2D>()
    images.forEachIndexed { i, image ->
        val data = context.createImageData(width.toDouble(), height.toDouble())
        // Through a plain byte view of it: the clamped array would clamp a byte above 127, negative in Kotlin, to 0.
        val bytes = Uint8Array(data.data.buffer, data.data.byteOffset, data.data.length)
        image.pixels.forEachIndexed { p, pixel ->
            bytes[p * 4] = red(pixel).toByte()
            bytes[p * 4 + 1] = green(pixel).toByte()
            bytes[p * 4 + 2] = blue(pixel).toByte()
            bytes[p * 4 + 3] = 0xFF.toByte()
        }
        val (x, y) = if (arrangement == Arrangement.ROW) width * i to 0 else 0 to height * i
        context.putImageData(data, x.toDouble(), y.toDouble())
    }
    return canvas
}

/** Hands [canvas] to the browser to save as a PNG called [name]; calls [onFailure] if it cannot be encoded. */
fun download(canvas: HTMLCanvasElement, name: String, onFailure: () -> Unit) {
    canvas.toBlob({ blob -> if (blob == null) onFailure() else download(blob, name) }, "image/png")
}

/** Hands [blob] to the browser to save as a file called [name]. */
fun download(blob: Blob, name: String) {
    val url = URL.createObjectURL(blob)
    val link = document.createElement("a") as HTMLAnchorElement
    link.href = url
    link.download = name
    link.click()
    window.setTimeout({ URL.revokeObjectURL(url) }, DOWNLOAD_URL_LIFETIME_MS)
}
