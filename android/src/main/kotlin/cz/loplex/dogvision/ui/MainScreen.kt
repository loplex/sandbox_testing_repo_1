package cz.loplex.dogvision.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.loplex.dogvision.MainViewModel
import cz.loplex.dogvision.PREVIEW_LONGEST_SIDE
import cz.loplex.dogvision.R
import cz.loplex.dogvision.Source
import cz.loplex.dogvision.savingNeedsPermission
import cz.loplex.dogvision.camera.CameraFeed
import cz.loplex.dogvision.video.VideoFeed

private val CAPTION_HEIGHT = 40.dp
private val GAP = 6.dp

@Composable
fun MainScreen(model: MainViewModel) {
    val context = LocalContext.current
    val source by model.source.collectAsStateWithLifecycle()
    val converting by model.converting.collectAsStateWithLifecycle()
    var cameraAllowed by remember {
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        mutableStateOf(permission == PackageManager.PERMISSION_GRANTED)
    }
    val askForCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraAllowed = it
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(model::openMedia)
    }
    val save = rememberSaving(model)
    Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        WithControls(
            model,
            buttons = {
                ImageButton(R.drawable.ic_photo, R.string.open_media) {
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }
                if (source != Source.Camera) ImageButton(R.drawable.ic_camera, R.string.show_camera, model::openCamera)
                ImageButton(R.drawable.ic_save, R.string.save_snapshot) { save(model::saveSnapshot) }
                when {
                    converting -> ImageButton(R.drawable.ic_cancel, R.string.cancel_conversion, model::cancelConversion)
                    source is Source.Photo -> ImageButton(R.drawable.ic_full_size, R.string.save_full_size) {
                        save(model::convertPhoto)
                    }
                    source is Source.Video -> ImageButton(R.drawable.ic_full_size, R.string.save_video_full_size) {
                        save(model::convertVideo)
                    }
                }
            },
        ) {
            Images(model)
            when (val shown = source) {
                Source.Camera -> if (cameraAllowed) {
                    Camera(model)
                } else {
                    AskForCamera { askForCamera.launch(Manifest.permission.CAMERA) }
                }
                is Source.Video -> Video(model, shown)
                is Source.Photo -> Unit
            }
        }
        val message by model.message.collectAsStateWithLifecycle()
        message?.let {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .clickable(onClick = model::dismissMessage),
            ) {
                Text(it, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

/**
 * Runs a saving action, first asking for the storage permission where saving to Pictures needs it;
 * without it the action is not run, and the model says why.
 */
@Composable
private fun rememberSaving(model: MainViewModel): (() -> Unit) -> Unit {
    val context = LocalContext.current
    val storageNeeded = stringResource(R.string.storage_needed)
    var waiting by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) waiting?.invoke() else model.say(storageNeeded)
        waiting = null
    }
    return { action ->
        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        if (!savingNeedsPermission || granted) {
            action()
        } else {
            waiting = action
            ask.launch(permission)
        }
    }
}

/** Why the camera is needed, and a button that asks for it. */
@Composable
private fun AskForCamera(onAsk: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier.align(Alignment.Center).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.camera_needed), color = Color.White, textAlign = TextAlign.Center)
            Button(onClick = onAsk) { Text(stringResource(R.string.allow_camera)) }
        }
    }
}

@Composable
private fun ImageButton(icon: Int, description: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(icon), contentDescription = stringResource(description), tint = Color.White)
    }
}

/**
 * [images] with the controls beside them on a wide screen and under them on a tall one, where they
 * take at most [CONTROLS_SHARE] of the height. [buttons] go over the images' top right corner,
 * followed by one that hides or shows the controls.
 */
@Composable
private fun WithControls(model: MainViewModel, buttons: @Composable () -> Unit, images: @Composable () -> Unit) {
    val view by model.view.collectAsStateWithLifecycle()
    var shown by rememberSaveable { mutableStateOf(true) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight
        val controlsHeight = maxHeight * CONTROLS_SHARE
        val imagesWithButtons = @Composable { boxModifier: Modifier ->
            Box(boxModifier) {
                images()
                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.45f), MaterialTheme.shapes.large),
                ) {
                    buttons()
                    ImageButton(R.drawable.ic_tune, R.string.side_panel) { shown = !shown }
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
                imagesWithButtons(Modifier.weight(1f).fillMaxHeight())
                if (shown) controls(Modifier.width(CONTROLS_WIDTH).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                imagesWithButtons(Modifier.weight(1f).fillMaxWidth())
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

/** The video, feeding the model's frames while the screen is started, and paused while it is stopped. */
@Composable
private fun Video(model: MainViewModel, video: Source.Video) {
    val context = LocalContext.current.applicationContext
    val feed = remember(video) {
        VideoFeed(context, video.uri, model.frames, PREVIEW_LONGEST_SIDE, model::onVideoError)
    }
    // Declared first, so disposed of last: the feed is paused before it is released.
    DisposableEffect(feed) {
        onDispose(feed::release)
    }
    LifecycleStartEffect(feed) {
        feed.play()
        onStopOrDispose { feed.pause() }
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
            bindCapture = { model.capture = it },
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
