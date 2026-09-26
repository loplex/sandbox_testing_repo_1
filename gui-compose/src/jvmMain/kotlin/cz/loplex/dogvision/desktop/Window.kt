package cz.loplex.dogvision.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cz.loplex.dogvision.cli.Arguments
import cz.loplex.dogvision.cli.readPhoto
import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts
import cz.loplex.dogvision.ui.Controls
import cz.loplex.dogvision.ui.LocalTexts
import java.awt.EventQueue
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.concurrent.thread

/** What the window shows: a photo, a video played over and over, or a camera. */
sealed interface Source {
    class Photo(val image: Image) : Source

    class Video(val file: File) : Source

    class Camera(val index: Int) : Source
}

/** Why nothing is shown, worded when it is shown, in the language chosen then. */
private typealias Failure = (Texts) -> String

/** The height of a caption under each image, and the gap between images, as in the Android app. */
private val CAPTION_HEIGHT = 40.dp
private val GAP = 6.dp

/**
 * Opens the window on what [arguments] ask for: the file given with --window, a photo or else a video, or the camera
 * --camera names; returns once the window is closed. q or Escape closes it, as in the Python program's window.
 */
fun showWindow(arguments: Arguments): Int {
    val file = arguments.file
    var failure: Failure? = null
    val source = when {
        file == null -> Source.Camera(arguments.camera)

        else -> try {
            readPhoto(file)?.let(Source::Photo) ?: Source.Video(file)
        } catch (error: IOException) {
            failure = { texts -> texts.get(Str.MEDIA_FAILED, file.name) + ": ${error.message}" }
            null
        }
    }
    application(exitProcessOnExit = false) {
        var language by remember { mutableStateOf("") }
        val texts = remember(language) {
            Texts.forLanguages(listOf(language.ifEmpty { Locale.getDefault().toLanguageTag() }))
        }
        CompositionLocalProvider(LocalTexts provides texts) {
            Window(
                onCloseRequest = ::exitApplication,
                title = texts.get(Str.APP_NAME),
                state = rememberWindowState(width = 1280.dp, height = 800.dp),
                onKeyEvent = { event ->
                    val quits = event.type == KeyEventType.KeyDown && (event.key == Key.Escape || event.key == Key.Q)
                    if (quits) exitApplication()
                    quits
                },
            ) {
                MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                    Surface {
                        Screen(source, failure, arguments, language) { language = it }
                    }
                }
            }
        }
    }
    return 0
}

@Composable
private fun Screen(
    source: Source?,
    startFailure: Failure?,
    arguments: Arguments,
    language: String,
    onLanguage: (String) -> Unit,
) {
    var view by remember { mutableStateOf(arguments.windowView) }
    var picture by remember { mutableStateOf<Picture?>(null) }
    var failure by remember { mutableStateOf(startFailure) }
    val renderer = remember {
        Renderer(
            onPicture = { EventQueue.invokeLater { picture = it } },
            onFailure = { message -> EventQueue.invokeLater { failure = { it.get(Str.DRAW_FAILED, message) } } },
        )
    }
    DisposableEffect(renderer) {
        val feed = startFeed(source, renderer) { failed -> EventQueue.invokeLater { failure = failed } }
        onDispose {
            feed.close()
            renderer.close()
        }
    }
    LaunchedEffect(view) { renderer.setView(view) }
    Row {
        Preview(picture, failure, Modifier.weight(1f).fillMaxHeight(), renderer::setArea)
        Controls(
            view = view,
            recording = false,
            onChange = { change -> view = change(view) },
            onReset = { view = arguments.windowView },
            language = language,
            onLanguage = onLanguage,
            modifier = Modifier.width(380.dp).fillMaxHeight(),
        )
    }
}

/** The images laid out as [picture] has them, with their captions, or why there are none. */
@Composable
private fun Preview(picture: Picture?, failure: Failure?, modifier: Modifier, onArea: (Area) -> Unit) {
    val density = LocalDensity.current
    val texts = LocalTexts.current
    val captionHeight = with(density) { CAPTION_HEIGHT.roundToPx() }
    val gap = with(density) { GAP.roundToPx() }
    Box(modifier.onSizeChanged { onArea(Area(it.width, it.height, captionHeight, gap)) }) {
        if (failure != null) {
            Text(failure(texts), Modifier.align(Alignment.Center).padding(24.dp), textAlign = TextAlign.Center)
            return@Box
        }
        if (picture == null) return@Box
        Canvas(Modifier.fillMaxSize()) { drawImage(picture.bitmap) }
        val captions = texts.captions(picture.view, picture.differenceShare)
        picture.layout.captions.zip(captions).forEach { (box, caption) ->
            Text(
                caption,
                Modifier
                    .offset { IntOffset(box.left, box.top) }
                    .size(with(density) { box.width.toDp() }, with(density) { box.height.toDp() })
                    .wrapContentHeight(),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Starts showing [source] through [renderer], on a thread of its own, since ffmpeg takes a moment to open a camera;
 * [onFailure] is told why it cannot be shown. The feed it returns stops what it started.
 */
private fun startFeed(source: Source?, renderer: Renderer, onFailure: (Failure) -> Unit): AutoCloseable {
    var feed: FfmpegFeed? = null
    var closed = false
    val lock = Any()
    thread(name = "dog-vision-source", isDaemon = true) {
        val started = try {
            when (source) {
                null -> null

                is Source.Photo -> null.also { renderer.show(frameOf(preview(source.image)), live = false) }

                is Source.Video -> FfmpegFeed.video(
                    source.file,
                    { renderer.show(it, live = true) },
                    { reason -> onFailure { it.get(Str.VIDEO_FAILED, source.file.name, reason) } },
                )

                is Source.Camera -> FfmpegFeed.camera(
                    source.index,
                    { renderer.show(it, live = true) },
                    { reason -> onFailure { it.get(Str.CAMERA_FAILED, reason) } },
                )
            }
        } catch (error: IOException) {
            val reason = error.message.orEmpty()
            onFailure { texts ->
                when (source) {
                    is Source.Camera -> texts.get(Str.CAMERA_FAILED, reason)
                    is Source.Video -> texts.get(Str.MEDIA_FAILED, source.file.name) + ": $reason"
                    else -> reason
                }
            }
            null
        }
        synchronized(lock) {
            if (closed) started?.close() else feed = started
        }
    }
    // A feed that starts after this is closed closes itself.
    return AutoCloseable {
        synchronized(lock) {
            closed = true
            feed?.close()
        }
    }
}
