package cz.loplex.dogvision

import androidx.lifecycle.ViewModel
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.render.Drawn
import cz.loplex.dogvision.render.FrameExchange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the screen shows, kept while the activity is recreated. */
class MainViewModel : ViewModel() {
    /** The frames of the source shown, from the camera. */
    val frames = FrameExchange()

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view.asStateFlow()

    private val _drawn = MutableStateFlow<Drawn?>(null)

    /** What the renderer drew last: the layout of the images, and the share that differs. */
    val drawn: StateFlow<Drawn?> = _drawn.asStateFlow()

    private val _cameraError = MutableStateFlow<String?>(null)
    val cameraError: StateFlow<String?> = _cameraError.asStateFlow()

    fun update(change: (View) -> View) = _view.update(change)

    /** Called on the GL thread. */
    fun onDrawn(drawn: Drawn) {
        _drawn.value = drawn
    }

    fun onCameraError(error: Throwable) {
        _cameraError.value = error.message ?: error.javaClass.simpleName
    }
}
