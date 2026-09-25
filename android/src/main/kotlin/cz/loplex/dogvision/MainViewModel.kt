package cz.loplex.dogvision

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.render.Drawn
import cz.loplex.dogvision.render.FrameExchange
import kotlinx.coroutines.Dispatchers
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
}
