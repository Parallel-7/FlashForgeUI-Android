package me.ghost.ffui.ui.components

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.jdtech.mpv.MPVLib

/**
 * libmpv (`dev.jdtech.mpv:libmpv`) camera playback, split into a [MpvController] (one mpv
 * instance + network stream) and one or more [MpvVideoSurface]s that render it.
 *
 * ## Why the split
 * The dashboard shows the feed in a small card, and tapping expand shows it fullscreen in a
 * `Dialog` — a *separate* composition subtree. Naively each would own its own mpv instance, so
 * expanding would open a **second** connection to the camera while the card's connection is
 * still live. FlashForge camera servers generally allow a single connection, so the second one
 * fails and the view goes black; even when it doesn't, the stream visibly restarts.
 *
 * Instead, [MpvController] owns a single mpv instance. Each visible [MpvVideoSurface] registers
 * its `TextureView` surface with the controller; the most recently registered surface is the
 * active render target. Expanding to fullscreen just **swaps the output surface** — mpv keeps
 * decoding the same network stream, so there's no reconnect and no black frame. The card's
 * surface stays registered underneath the dialog and reclaims output when fullscreen closes.
 *
 * mpv renders into a [TextureView] (not a `SurfaceView`) so the parent's `Modifier.clip(...)`
 * clips the video to rounded corners; `hwdec=mediacodec-copy` copies frames back for the gpu VO
 * rather than punching through a dedicated surface.
 */
@Composable
fun rememberMpvController(): MpvController {
    val context = LocalContext.current
    val controller = remember { MpvController(context) }
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
    return controller
}

/**
 * A render target for [controller]. While composed, its surface is the controller's active
 * output (the last-composed surface wins, so a fullscreen overlay takes over from the card and
 * hands back on dispose).
 *
 * @param cropToFill true zooms the frame to cover the surface with no bars (the fixed-ratio
 *   card); false fits the whole frame letterboxed (fullscreen, where cropping would chop the
 *   top/bottom of the print on a tall display).
 */
@Composable
fun MpvVideoSurface(
    controller: MpvController,
    modifier: Modifier = Modifier,
    cropToFill: Boolean = true
) {
    val context = LocalContext.current
    val textureView = remember { TextureView(context) }

    DisposableEffect(textureView, cropToFill) {
        val listener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                controller.bindSurface(textureView, Surface(st), width, height, cropToFill)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                controller.updateSurfaceSize(textureView, width, height)
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                controller.unbindSurface(textureView)
                return true
            }

            // Fires once per frame pushed to this surface — the basis for the live FPS readout.
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                controller.onFrameRendered()
            }
        }
        textureView.surfaceTextureListener = listener
        // The surface may already be live when reusing a view across recomposition.
        if (textureView.isAvailable) {
            controller.bindSurface(
                textureView, Surface(textureView.surfaceTexture!!),
                textureView.width, textureView.height, cropToFill
            )
        }
        onDispose {
            textureView.surfaceTextureListener = null
            controller.unbindSurface(textureView)
        }
    }

    AndroidView(modifier = modifier, factory = { textureView })
}

/**
 * Owns a single libmpv instance and its network stream. Surfaces are registered via
 * [bindSurface] / [unbindSurface]; the stream itself ([setMedia]) is independent of which
 * surface is active, so moving between the card and fullscreen never restarts playback.
 *
 * All mpv calls happen on the main thread (the surface callbacks and Compose effects that drive
 * this all run there), mirroring mpv-android's `BaseMPVView`.
 */
class MpvController(context: Context) {
    private val mpv: MPVLib? = MPVLib.create(context.applicationContext)

    private class Binding(
        val owner: Any,
        val surface: Surface,
        var width: Int,
        var height: Int,
        val cropToFill: Boolean
    )

    /** Registered render targets; the last element is the active output. */
    private val bindings = ArrayDeque<Binding>()

    private var url: String? = null
    private var playing = false
    private var loaded = false

    /** Nanosecond timestamps of frames rendered in the last second, for [currentFps]. */
    private val frameTimestamps = ArrayDeque<Long>()

    init {
        mpv?.let { m ->
            // Render options must be set before init().
            m.setOptionString("config", "no")
            m.setOptionString("vo", "gpu")
            m.setOptionString("gpu-context", "android")
            m.setOptionString("opengl-es", "yes")
            m.setOptionString("hwdec", "mediacodec-copy")
            // Live LAN camera: minimise latency, don't buffer ahead.
            m.setOptionString("profile", "low-latency")
            m.setOptionString("cache", "no")
            m.setOptionString("untimed", "yes")
            // Default to crop-to-fill; per-surface panscan is set in activate().
            m.setOptionString("panscan", "1.0")
            // Camera is plain HTTP; no audio needed.
            m.setOptionString("audio", "no")

            m.init()

            // Don't create a VO window until a surface actually exists.
            m.setOptionString("force-window", "no")
            m.setOptionString("idle", "yes")
        }
    }

    /** Set the desired stream + play state. Cheap and idempotent; safe to call on recomposition. */
    fun setMedia(streamUrl: String?, isPlaying: Boolean) {
        url = streamUrl?.takeIf { it.isNotBlank() }
        playing = isPlaying
        reconcile()
    }

    /** Record a frame hitting the active surface (called from the TextureView callback). */
    fun onFrameRendered() {
        val now = System.nanoTime()
        frameTimestamps.addLast(now)
        trimFrames(now)
    }

    /**
     * Measured render rate: frames pushed to the surface in the last second. Counted from the
     * surface callbacks rather than mpv's `estimated-vf-fps`, which reads 0 under `untimed` — the
     * mode the live feed runs in for low latency.
     */
    fun currentFps(): Double {
        trimFrames(System.nanoTime())
        return frameTimestamps.size.toDouble()
    }

    private fun trimFrames(now: Long) {
        val cutoff = now - 1_000_000_000L
        while (frameTimestamps.isNotEmpty() && frameTimestamps.first() < cutoff) {
            frameTimestamps.removeFirst()
        }
    }

    // ── Surface stack: one mpv output, swapped between card & fullscreen ──────

    fun bindSurface(owner: Any, surface: Surface, width: Int, height: Int, cropToFill: Boolean) {
        removeBinding(owner)?.surface?.release()
        deactivate()
        bindings.addLast(Binding(owner, surface, width, height, cropToFill))
        activate()
    }

    fun unbindSurface(owner: Any) {
        val wasActive = bindings.lastOrNull()?.owner === owner
        if (wasActive) deactivate()
        removeBinding(owner)?.surface?.release()
        if (wasActive) activate()
    }

    fun updateSurfaceSize(owner: Any, width: Int, height: Int) {
        val b = bindings.firstOrNull { it.owner === owner } ?: return
        b.width = width
        b.height = height
        if (bindings.lastOrNull() === b) {
            mpv?.setPropertyString("android-surface-size", "${width}x$height")
        }
    }

    private fun removeBinding(owner: Any): Binding? {
        val b = bindings.firstOrNull { it.owner === owner } ?: return null
        bindings.remove(b)
        return b
    }

    private fun activate() {
        val m = mpv ?: return
        val b = bindings.lastOrNull() ?: return
        // Order mirrors mpv-android's BaseMPVView.surfaceCreated: attach the surface and open the
        // window BEFORE re-enabling the VO. Setting vo=gpu first (no surface attached) makes mpv
        // init the GL output with nowhere to draw → it fails and the view stays black.
        m.attachSurface(b.surface)
        m.setOptionString("force-window", "yes")
        m.setPropertyString("vo", "gpu")
        m.setPropertyString("android-surface-size", "${b.width}x${b.height}")
        m.setPropertyDouble("panscan", if (b.cropToFill) 1.0 else 0.0)
        reconcile()
    }

    private fun deactivate() {
        val m = mpv ?: return
        if (bindings.isEmpty()) return
        m.setPropertyString("vo", "null")
        m.setOptionString("force-window", "no")
        m.detachSurface()
    }

    /**
     * Start/stop the network stream to match the desired state. A `loadfile` is issued only when
     * nothing is loaded yet, and `stop` only when the user actually paused/cleared the URL — a
     * pure surface swap (card ↔ fullscreen) leaves `loaded` untouched, so the stream is preserved.
     */
    private fun reconcile() {
        val m = mpv ?: return
        val current = url
        val hasOutput = bindings.isNotEmpty()
        val shouldPlay = playing && current != null && hasOutput
        if (shouldPlay && !loaded) {
            m.command(arrayOf("loadfile", current!!))
            loaded = true
        } else if (loaded && !(playing && current != null)) {
            // User paused or cleared the stream — not merely surface-less mid-swap.
            m.command(arrayOf("stop"))
            loaded = false
        }
    }

    fun release() {
        val m = mpv ?: return
        runCatching { m.command(arrayOf("stop")) }
        loaded = false
        if (bindings.isNotEmpty()) {
            runCatching { m.detachSurface() }
        }
        bindings.forEach { it.surface.release() }
        bindings.clear()
        runCatching { m.destroy() }
    }
}
