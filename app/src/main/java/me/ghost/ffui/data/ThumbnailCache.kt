package me.ghost.ffui.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Two-tier cache for printer file thumbnails (`/gcodeThumb` PNGs), keyed by a stable
 * `serial:fileName` string:
 *
 *  - **Memory** — an [LruCache] of decoded [Bitmap]s, bounded to a fraction of heap, so a thumbnail
 *    that scrolls out of a list and back in redraws instantly with no network call.
 *  - **Disk** — the raw PNG bytes under `cacheDir/thumbnails`, surviving process death. Entries are
 *    purged once per process on first use if older than [MAX_AGE_MS] (30 days) to bound storage.
 *
 * A short-lived in-memory negative set remembers files the printer reported as having no thumbnail,
 * so we don't re-hit the network for them within a session. Thumbnails are content-addressed by
 * name only, so a same-named file replaced on the printer can show a stale image until purge —
 * acceptable for a preview, and the Files screen's refresh is unaffected (it only re-lists).
 */
object ThumbnailCache {
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000 // 30 days

    private val maxKb = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(4096)
    private val memory = object : LruCache<String, Bitmap>(maxKb) {
        override fun sizeOf(key: String, value: Bitmap) = (value.allocationByteCount / 1024).coerceAtLeast(1)
    }
    private val negative: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile private var diskDir: File? = null
    @Volatile private var purged = false

    /** Synchronous memory-only lookup — used as a composable's initial value to avoid flicker. */
    fun peek(key: String): Bitmap? = memory.get(key)

    /**
     * Returns the thumbnail for [key], pulling from memory, then disk, then [fetch] (network).
     * A successful network fetch is written through to both tiers; a `null` result is remembered
     * negatively. Returns `null` when the file has no thumbnail or decoding fails.
     */
    suspend fun get(context: Context, key: String, fetch: suspend () -> ByteArray?): Bitmap? {
        memory.get(key)?.let { return it }
        if (key in negative) return null

        val dir = ensureDir(context)
        val file = File(dir, hash(key))

        // Disk hit (fresh).
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS) {
            val bmp = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() }
                ?.let { decode(it) }
            if (bmp != null) {
                memory.put(key, bmp)
                return bmp
            }
        }

        // Network.
        val bytes = fetch()
        if (bytes == null) {
            negative.add(key)
            return null
        }
        withContext(Dispatchers.IO) { runCatching { file.writeBytes(bytes) } }
        return decode(bytes)?.also { memory.put(key, it) }
    }

    /** Decodes PNG bytes; runs on [Dispatchers.IO] — callers reach this from the main thread. */
    private suspend fun decode(bytes: ByteArray): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
    }

    private fun ensureDir(context: Context): File {
        diskDir?.let { return it }
        val dir = File(context.applicationContext.cacheDir, "thumbnails").apply { mkdirs() }
        diskDir = dir
        if (!purged) {
            purged = true
            // Fire-and-forget purge of stale entries; cheap and bounded by directory size.
            val cutoff = System.currentTimeMillis() - MAX_AGE_MS
            Thread {
                runCatching {
                    dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
                }
            }.apply { isDaemon = true }.start()
        }
        return dir
    }

    /** SHA-256 hex of the key — a filesystem-safe, collision-resistant disk filename. */
    private fun hash(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
