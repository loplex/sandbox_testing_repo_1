package cz.loplex.dogvision.web

import cz.loplex.dogvision.core.ScreenLayout
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.layOut
import cz.loplex.dogvision.core.percent
import cz.loplex.dogvision.core.snapshotName
import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.Uint8Array
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.url.URL
import org.w3c.files.File
import org.w3c.files.get
import kotlin.math.max
import kotlin.math.roundToInt

/** The longest side a photo is shown at, as in the Android app, which keeps the controls quick. */
private const val PREVIEW_LONGEST_SIDE = 1280

/** The height of a caption under each image, and the gap between images, in CSS pixels, as the app's in dp. */
private const val CAPTION_HEIGHT = 40
private const val GAP = 6

/** A photo shown, scaled down to [PREVIEW_LONGEST_SIDE], as tightly packed RGBA with its first row first. */
private class Photo(val width: Int, val height: Int, val pixels: Uint8Array)

/**
 * The page: a photo shown as the chosen species sees it, and the controls of the view.
 *
 * The images are composed on the GPU once for each view and photo, and drawn again into the canvas whenever its size
 * changes; the captions are text over the canvas, under the boxes [layOut] gives the images.
 */
class Page(private var texts: Texts) {
    private val stage = element<HTMLElement>("stage")
    private val canvas = element<HTMLCanvasElement>("view")
    private val captions = element<HTMLElement>("captions")
    private val prompt = element<HTMLElement>("prompt")
    private val notice = element<HTMLElement>("notice")
    private val open = element<HTMLInputElement>("open")
    private val save = element<HTMLButtonElement>("save")
    private val panel = element<HTMLElement>("controls")

    private val gl: WebGL2RenderingContext? = canvas.getContext(
        "webgl2",
        js("({ alpha: true, antialias: false, depth: false, stencil: false })"),
    )?.unsafeCast<WebGL2RenderingContext>()
    private var passes: Passes? = null
    private var photo: Photo? = null

    private var view = View()

    /** The view the images were composed for last, and the share of pixels its map marks. */
    private var composed: View? = null
    private var share: Double? = null
    private var drawScheduled = false

    /**
     * What the page says in place of the images, worded anew when the language changes: what to do first, or why it
     * cannot draw; null once a photo is shown.
     */
    private var message: ((Texts) -> String)? = { it.get("choose_photo") }

    /** What went wrong last, shown under the images until it is clicked away or a photo is opened, as in the app. */
    private var noticeText: ((Texts) -> String)? = null

    /** The language the viewer chose, or null for the browser's, kept here as well where storage is blocked. */
    private var language = Texts.chosenLanguage

    private var controls = controls()

    fun start() {
        showTexts()
        val gl = gl
        if (gl == null) {
            say { it.get("no_webgl2") }
            open.disabled = true
            return
        }
        passes = try {
            Passes(gl)
        } catch (error: IllegalStateException) {
            // A shader the GPU's driver cannot compile or link.
            say { it.get("gl_failed", error.message.orEmpty()) }
            open.disabled = true
            return
        }
        canvas.addEventListener("webglcontextlost", { event ->
            // Asks the browser to restore the context, which it does only for a page that prevents this.
            event.preventDefault()
            passes = null
        })
        canvas.addEventListener("webglcontextrestored", {
            passes = Passes(gl).also { passes -> photo?.let { passes.upload(it.width, it.height, it.pixels) } }
            composed = null
            invalidate()
        })
        open.addEventListener("change", {
            open.files?.get(0)?.let(::openFile)
            open.value = ""
        })
        save.addEventListener("click", { saveSnapshot() })
        notice.addEventListener("click", { showNotice(null) })
        stage.addEventListener("dragover", Event::preventDefault)
        stage.addEventListener("drop", { event ->
            event.preventDefault()
            event.asDynamic().dataTransfer?.files?.item(0)?.unsafeCast<File>()?.let(::openFile)
        })
        ResizeObserver { _, _ -> invalidate() }.observe(stage)
        invalidate()
    }

    private fun controls() = Controls(
        panel,
        texts,
        language,
        onChange = { change ->
            view = change(view)
            invalidate()
        },
        onLanguage = ::switchLanguage,
    )

    /** Words the page in the language of [texts]. */
    private fun showTexts() {
        document.documentElement?.setAttribute("lang", texts.language)
        document.title = texts.get("app_name")
        element<HTMLElement>("title").textContent = texts.get("app_name")
        element<HTMLElement>("open-text").textContent = texts.get("open_photo")
        save.textContent = texts.get("save_snapshot")
        prompt.textContent = message?.invoke(texts).orEmpty()
        notice.title = texts.get("close")
        showNotice(noticeText)
        controls.show(view)
    }

    /**
     * Speaks [language], or the browser's if null, from now on and the next time the page opens; the photo and the view
     * stay as they are, as in the Android app.
     */
    private fun switchLanguage(language: String?) {
        this.language = language
        Texts.chosenLanguage = language
        texts = Texts.of(language ?: Texts.browserLanguage())
        panel.innerHTML = ""
        controls = controls()
        showTexts()
        invalidate()
    }

    /** Decodes [file], turned as its EXIF orientation says, as the browser draws an image. */
    private fun openFile(file: File) {
        val url = URL.createObjectURL(file)
        val image = document.createElement("img") as HTMLImageElement
        image.onload = {
            URL.revokeObjectURL(url)
            show(Photo(image))
        }
        image.onerror = { _, _, _, _, _ ->
            URL.revokeObjectURL(url)
            showNotice { it.get("photo_failed", file.name) }
        }
        image.src = url
    }

    private fun show(photo: Photo) {
        this.photo = photo
        passes?.upload(photo.width, photo.height, photo.pixels)
        composed = null
        message = null
        prompt.textContent = ""
        showNotice(null)
        invalidate()
    }

    /**
     * Saves the view as shown, at the size the images are composed: the photo's scaled-down view, as the Android app's
     * snapshot of a photo.
     */
    private fun saveSnapshot() {
        val passes = passes ?: return
        val photo = photo ?: return
        composeIfChanged(passes)
        val arrangement = layout(canvas.width, canvas.height, photo).arrangement
        download(stitch(passes.readImages(view.images), arrangement), snapshotName(view, now())) {
            showNotice { it.get("snapshot_failed", it.get("snapshot_not_encoded")) }
        }
    }

    private fun showNotice(text: ((Texts) -> String)?) {
        noticeText = text
        notice.textContent = text?.invoke(texts).orEmpty()
        notice.hidden = text == null
    }

    private fun say(message: (Texts) -> String) {
        this.message = message
        prompt.textContent = message(texts)
        invalidate()
    }

    /** Draws the page anew before the next frame, once however many changes there are until then. */
    private fun invalidate() {
        if (drawScheduled) return
        drawScheduled = true
        window.requestAnimationFrame {
            drawScheduled = false
            draw()
        }
    }

    private fun draw() {
        controls.show(view)
        val scale = window.devicePixelRatio
        val width = (stage.clientWidth * scale).roundToInt()
        val height = (stage.clientHeight * scale).roundToInt()
        if (canvas.width != width || canvas.height != height) {
            canvas.width = width
            canvas.height = height
        }
        val passes = passes
        val photo = photo
        prompt.hidden = photo != null && prompt.textContent.isNullOrEmpty()
        save.disabled = passes == null || photo == null
        if (passes == null || photo == null) {
            captions.innerHTML = ""
            return
        }
        val layout = layout(width, height, photo)
        composeIfChanged(passes)
        passes.draw(layout.images, width, height)
        captions.innerHTML = ""
        captionTexts().zip(layout.captions) { text, box ->
            val caption = document.createElement("div") as HTMLElement
            caption.className = "caption"
            caption.textContent = text
            caption.style.left = "${box.left / scale}px"
            caption.style.top = "${box.top / scale}px"
            caption.style.width = "${box.width / scale}px"
            caption.style.height = "${box.height / scale}px"
            captions.appendChild(caption)
        }
    }

    /** Where the view's images of [photo] and their captions go on a canvas of [width] x [height] device pixels. */
    private fun layout(width: Int, height: Int, photo: Photo): ScreenLayout {
        val scale = window.devicePixelRatio
        return layOut(
            width,
            height,
            photo.width,
            photo.height,
            view.images,
            (CAPTION_HEIGHT * scale).roundToInt(),
            (GAP * scale).roundToInt(),
        )
    }

    private fun composeIfChanged(passes: Passes) {
        if (view == composed) return
        share = passes.compose(view)
        composed = view
    }

    /** What each image of the view shows, left to right or top to bottom, as the Android app's captions. */
    private fun captionTexts(): List<String> {
        val right = texts.speciesLabel(view.params.species)
        if (!view.sideBySide) return listOf(right)
        val left = view.compare?.let(texts::speciesLabel) ?: texts.get("original")
        val share = share
        if (!view.difference || share == null) return listOf(left, right)
        return listOf(left, right, texts.get("difference_caption", percent(share, share, FactWording(texts))))
    }
}

/** The photo in [image], scaled down to [PREVIEW_LONGEST_SIDE], its pixels read back as the browser decoded them. */
private fun Photo(image: HTMLImageElement): Photo {
    val longest = max(image.naturalWidth, image.naturalHeight)
    val scale = minOf(1.0, PREVIEW_LONGEST_SIDE.toDouble() / longest)
    val width = max(1, (image.naturalWidth * scale).roundToInt())
    val height = max(1, (image.naturalHeight * scale).roundToInt())
    val canvas = document.createElement("canvas") as HTMLCanvasElement
    canvas.width = width
    canvas.height = height
    val context = canvas.getContext("2d").unsafeCast<CanvasRenderingContext2D>()
    context.imageSmoothingEnabled = true
    context.asDynamic().imageSmoothingQuality = "high"
    context.drawImage(image, 0.0, 0.0, width.toDouble(), height.toDouble())
    val data = context.getImageData(0.0, 0.0, width.toDouble(), height.toDouble()).data
    return Photo(width, height, Uint8Array(data.buffer, data.byteOffset, data.length))
}

private fun <T : HTMLElement> element(id: String): T =
    checkNotNull(document.getElementById(id)) { "The page has no element $id" }.unsafeCast<T>()

/** Calls back when the size of an element it observes changes. */
private external class ResizeObserver(callback: (entries: Array<dynamic>, observer: ResizeObserver) -> Unit) {
    fun observe(target: HTMLElement)
}
