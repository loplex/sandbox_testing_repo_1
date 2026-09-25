package cz.loplex.dogvision

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.loplex.dogvision.core.Arrangement
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.compose
import cz.loplex.dogvision.core.composedSize
import cz.loplex.dogvision.core.meanLinearRgb
import cz.loplex.dogvision.core.percent
import cz.loplex.dogvision.render.Capture
import cz.loplex.dogvision.render.Drawn
import cz.loplex.dogvision.render.FrameExchange
import cz.loplex.dogvision.ui.AndroidTexts
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What is shown: the camera, or a photo. */
sealed interface Source {
    data object Camera : Source

    data class Photo(val uri: Uri, val name: String) : Source
}

/** What the screen shows, kept while the activity is recreated. */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    /** The frames of the source shown. */
    val frames = FrameExchange()

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view.asStateFlow()

    private val _source = MutableStateFlow<Source>(Source.Camera)
    val source: StateFlow<Source> = _source.asStateFlow()

    private val _drawn = MutableStateFlow<Drawn?>(null)

    /** What the renderer drew last: the layout of the images, and the share that differs. */
    val drawn: StateFlow<Drawn?> = _drawn.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    /** What the last action did, or why it failed, for the user to read. */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun update(change: (View) -> View) = _view.update(change)

    /** Every control back to where it started. */
    fun reset() {
        _view.value = View()
    }

    /** Called on the GL thread. */
    fun onDrawn(drawn: Drawn) {
        _drawn.value = drawn
    }

    fun onCameraError(error: Throwable) {
        val context = getApplication<Application>()
        _message.value = context.getString(R.string.camera_failed, error.message ?: error.javaClass.simpleName)
    }

    fun say(message: String) {
        _message.value = message
    }

    fun dismissMessage() {
        _message.value = null
    }

    /** Shows the photo at [uri] instead of the camera, scaled down to [PREVIEW_LONGEST_SIDE]. */
    fun openPhoto(uri: Uri) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            val (name, frame) = withContext(Dispatchers.IO) {
                val name = displayName(context, uri)
                val decoded = runCatching { decodePhoto(context, uri, PREVIEW_LONGEST_SIDE) }.getOrNull()
                name to decoded?.let { (bitmap, turn) -> bitmap.toFrame(turn).also { bitmap.recycle() } }
            }
            if (frame == null) {
                _message.value = context.getString(R.string.photo_failed, name)
                return@launch
            }
            _source.value = Source.Photo(uri, name)
            frames.publish(frame, frames.open())
            _message.value = null
        }
    }

    fun openCamera() {
        _source.value = Source.Camera
    }

    /** Asks the renderer for the images it draws next, with their layout; set while one is shown. */
    var capture: Capture? = null

    /**
     * Saves the view as shown to Pictures, at the size the images are rendered: the camera's
     * resolution, or a photo's scaled-down view.
     */
    fun saveSnapshot() {
        val context = getApplication<Application>()
        val name = snapshotName(view.value)
        val capture = capture ?: return
        capture { images, layout ->
            viewModelScope.launch(Dispatchers.IO) {
                _message.value = try {
                    savePng(context, stitch(images, layout.arrangement), name)
                    context.getString(R.string.saved, name, PICTURES_FOLDER)
                } catch (error: IOException) {
                    context.getString(R.string.snapshot_failed, error.message)
                }
            }
        }
    }

    private var conversion: Job? = null

    /**
     * Saves the photo shown to Pictures at its full size, as the view shows it now, on the CPU with
     * core's pipeline; what the conversion is at is told as a message.
     */
    fun convertPhoto() {
        val photo = source.value as? Source.Photo ?: return
        if (conversion?.isActive == true) return
        val context = getApplication<Application>()
        val texts = AndroidTexts(context)
        val view = view.value.copy(arrangement = drawn.value?.layout?.arrangement ?: Arrangement.ROW)
        val name = snapshotName(view, suffix = "-full")
        conversion = viewModelScope.launch(Dispatchers.Default) {
            _message.value = context.getString(R.string.converting, percent(0.0, 0.0, texts))
            _message.value = try {
                val (decoded, turn) = decodePhoto(context, photo.uri) ?: throw IOException(photo.name)
                val upright = upright(decoded, turn)
                val (width, height) = composedSize(view, upright.width, upright.height)
                val out = createBitmap(width, height)
                val mean = { meanLinearRgb(upright.width, upright.height, upright::getPixel) }
                compose(BitmapSource(upright), view, BitmapSink(out), mean) { rows ->
                    val done = rows.toDouble() / upright.height
                    _message.value = context.getString(R.string.converting, percent(done, done, texts))
                }
                savePng(context, out, name)
                context.getString(R.string.saved, name, PICTURES_FOLDER)
            } catch (error: IOException) {
                context.getString(R.string.conversion_failed, error.message)
            } catch (error: OutOfMemoryError) {
                context.getString(R.string.conversion_failed, context.getString(R.string.too_large))
            }
        }
    }

    /** The bitmap turned as [turn] says: rotated clockwise, then mirrored. */
    private fun upright(bitmap: Bitmap, turn: Pair<Int, Boolean>): Bitmap {
        val (rotation, mirrored) = turn
        if (rotation == 0 && !mirrored) return bitmap
        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
            if (mirrored) postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }
}
