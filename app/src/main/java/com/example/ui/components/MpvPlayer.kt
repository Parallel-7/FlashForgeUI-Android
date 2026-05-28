package com.example.ui.components

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.jdtech.mpv.MPVLib

/**
 * MJPEG/RTSP video surface backed by libmpv (`dev.jdtech.mpv:libmpv`).
 *
 * Replaces the former libVLC player. mpv crops-to-fill in its own render engine via
 * `panscan=1.0`, so the camera frame fills the card with no letterbox bars and no
 * distortion — the behaviour VLC's `setVideoScale` never delivered (its `VideoHelper`
 * assumed a fullscreen player and corrupted the aspect math for an embedded landscape card).
 *
 * ## Rendering target
 * mpv renders into a [TextureView]-backed [Surface] (not a `SurfaceView`): a TextureView is a
 * normal view in the hierarchy, so the parent's `Modifier.clip(RoundedCornerShape(...))`
 * actually clips the video to the card's rounded corners. `hwdec=mediacodec-copy` is used so
 * decoded frames are copied back for the gpu VO rather than punched through a dedicated
 * surface (which would require a SurfaceView and break the rounded clip).
 *
 * ## Instance model
 * libmpv v1.0.0 is instance-based — each [MpvPlayer] owns its own [MPVLib] handle, so multiple
 * camera cards (e.g. the multi-printer pager) can coexist. Playback is still gated by
 * [isPlaying] so only the visible feed actually streams.
 */
@Composable
fun MpvPlayer(
    videoUrl: String,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true
) {
    val context = LocalContext.current
    val textureView = remember { TextureView(context) }
    val controller = remember { MpvController(context) }

    DisposableEffect(controller) {
        controller.bind(textureView)
        onDispose { controller.release() }
    }

    LaunchedEffect(videoUrl, isPlaying) {
        controller.setMedia(videoUrl, isPlaying)
    }

    AndroidView(modifier = modifier, factory = { textureView })
}

/**
 * Owns a single libmpv instance and bridges its lifecycle to a [TextureView]'s surface and to
 * the composable's `videoUrl` / `isPlaying` inputs. All mpv calls happen on the main thread,
 * mirroring mpv-android's `BaseMPVView`.
 */
private class MpvController(context: Context) : TextureView.SurfaceTextureListener {
    private val mpv: MPVLib? = MPVLib.create(context.applicationContext)
    private var surface: Surface? = null
    private var attached = false

    private var pendingUrl: String? = null
    private var playing = false

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
            // Crop-to-fill: zoom the frame to cover the surface, cropping overflow — no bars.
            m.setOptionString("panscan", "1.0")
            // Camera is plain HTTP; no audio needed.
            m.setOptionString("audio", "no")

            m.init()

            // Don't create a VO window until the surface actually exists.
            m.setOptionString("force-window", "no")
            m.setOptionString("idle", "yes")
        }
    }

    fun bind(view: TextureView) {
        view.surfaceTextureListener = this
        // If the surface is already live (e.g. recomposition reusing the view), attach now.
        if (view.isAvailable && !attached) {
            onSurfaceTextureAvailable(view.surfaceTexture!!, view.width, view.height)
        }
    }

    fun setMedia(url: String, isPlaying: Boolean) {
        pendingUrl = url.ifBlank { null }
        playing = isPlaying
        applyPlayback()
    }

    private fun applyPlayback() {
        val m = mpv ?: return
        if (!attached) return
        val url = pendingUrl
        if (playing && url != null) {
            m.command(arrayOf("loadfile", url))
        } else {
            m.command(arrayOf("stop"))
        }
    }

    fun release() {
        val m = mpv ?: return
        runCatching { m.command(arrayOf("stop")) }
        if (attached) {
            runCatching { m.detachSurface() }
            attached = false
        }
        surface?.release()
        surface = null
        runCatching { m.destroy() }
    }

    // ── TextureView.SurfaceTextureListener ───────────────────────────────────

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val m = mpv ?: return
        val s = Surface(st)
        surface = s
        m.attachSurface(s)
        m.setOptionString("force-window", "yes")
        m.setPropertyString("android-surface-size", "${width}x$height")
        attached = true
        applyPlayback()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        mpv?.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        val m = mpv
        if (m != null && attached) {
            m.setPropertyString("vo", "null")
            m.setOptionString("force-window", "no")
            m.detachSurface()
            attached = false
        }
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}
