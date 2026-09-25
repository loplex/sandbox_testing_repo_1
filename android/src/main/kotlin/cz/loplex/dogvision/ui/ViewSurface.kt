package cz.loplex.dogvision.ui

import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.render.Drawn
import cz.loplex.dogvision.render.FrameExchange
import cz.loplex.dogvision.render.ViewRenderer

/**
 * The images of [view], drawn by the GPU from the newest of [frames], with room for a caption
 * [captionHeight] pixels tall under each and [gap] pixels between them.
 */
@Composable
fun ViewSurface(
    frames: FrameExchange,
    view: View,
    captionHeight: Int,
    gap: Int,
    onDrawn: (Drawn) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val renderer = remember(frames) { ViewRenderer(frames, onDrawn) }
    val surface = remember(renderer) {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    AndroidView(
        modifier = modifier,
        factory = {
            frames.onPublish = surface::requestRender
            surface
        },
        update = {
            renderer.view = view
            renderer.captionHeight = captionHeight
            renderer.gap = gap
            it.requestRender()
        },
        onRelease = { frames.onPublish = null },
    )
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> surface.onResume()
                Lifecycle.Event.ON_PAUSE -> surface.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
