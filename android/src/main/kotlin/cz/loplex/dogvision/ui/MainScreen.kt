package cz.loplex.dogvision.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.loplex.dogvision.MainViewModel
import cz.loplex.dogvision.R
import cz.loplex.dogvision.camera.CameraFeed

private val CAPTION_HEIGHT = 40.dp
private val GAP = 6.dp

@Composable
fun MainScreen(model: MainViewModel) {
    val context = LocalContext.current
    var cameraAllowed by remember {
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        mutableStateOf(permission == PackageManager.PERMISSION_GRANTED)
    }
    val askForCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraAllowed = it
    }
    Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        if (cameraAllowed) {
            Camera(model)
            WithControls(model) { Images(model) }
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.camera_needed), color = Color.White, textAlign = TextAlign.Center)
                Button(onClick = { askForCamera.launch(Manifest.permission.CAMERA) }) {
                    Text(stringResource(R.string.allow_camera))
                }
            }
        }
        val error by model.cameraError.collectAsStateWithLifecycle()
        error?.let {
            Text(
                stringResource(R.string.camera_failed, it),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        }
    }
}

/**
 * [images] with the controls beside them on a wide screen and under them on a tall one, where they
 * take at most [CONTROLS_SHARE] of the height; a button over the images hides or shows the controls.
 */
@Composable
private fun WithControls(model: MainViewModel, images: @Composable () -> Unit) {
    val view by model.view.collectAsStateWithLifecycle()
    var shown by rememberSaveable { mutableStateOf(true) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight
        val controlsHeight = maxHeight * CONTROLS_SHARE
        val imagesWithButton = @Composable { boxModifier: Modifier ->
            Box(boxModifier) {
                images()
                IconButton(onClick = { shown = !shown }, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(
                        painterResource(R.drawable.ic_tune),
                        contentDescription = stringResource(R.string.side_panel),
                        tint = Color.White,
                    )
                }
            }
        }
        val controls = @Composable { panelModifier: Modifier ->
            Surface(panelModifier, color = MaterialTheme.colorScheme.surface) {
                Controls(view, model::update, model::reset)
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                imagesWithButton(Modifier.weight(1f).fillMaxHeight())
                if (shown) controls(Modifier.width(CONTROLS_WIDTH).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                imagesWithButton(Modifier.weight(1f).fillMaxWidth())
                if (shown) controls(Modifier.fillMaxWidth().heightIn(max = controlsHeight))
            }
        }
    }
}

private val CONTROLS_WIDTH = 360.dp
private const val CONTROLS_SHARE = 0.45f

/** The camera, feeding the model's frames while the screen is started. */
@Composable
private fun Camera(model: MainViewModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val display = LocalView.current.display
    val configuration = LocalConfiguration.current
    val feed = remember(owner) { CameraFeed(context, owner, model.frames, model::onCameraError) }
    DisposableEffect(feed) {
        feed.start(front = false, rotation = display.rotation)
        onDispose(feed::release)
    }
    DisposableEffect(configuration.orientation) {
        feed.setRotation(display.rotation)
        onDispose { }
    }
}

/** The images as the renderer lays them out, each with its caption under it. */
@Composable
private fun Images(model: MainViewModel) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val view by model.view.collectAsStateWithLifecycle()
    val drawn by model.drawn.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        ViewSurface(
            frames = model.frames,
            view = view,
            captionHeight = with(density) { CAPTION_HEIGHT.roundToPx() },
            gap = with(density) { GAP.roundToPx() },
            onDrawn = model::onDrawn,
            modifier = Modifier.fillMaxSize(),
        )
        val shown = drawn ?: return@Box
        if (shown.layout.captions.size != view.images) return@Box // a layout of the view before
        context.captions(view, shown.differenceShare).zip(shown.layout.captions).forEach { (caption, box) ->
            with(density) {
                Text(
                    caption,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .offset { IntOffset(box.left, box.top) }
                        .size(box.width.toDp(), box.height.toDp())
                        .padding(top = 4.dp),
                )
            }
        }
    }
}
