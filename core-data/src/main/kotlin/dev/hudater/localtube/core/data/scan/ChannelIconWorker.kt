package dev.hudater.localtube.core.data.scan

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.hudater.localtube.core.data.db.ChannelDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Background pass that fetches a channel avatar for channels indexed
 * without one (bare yt-dlp downloads only know the channel name).
 * YouTube's channels-filter search page embeds the hit channels' avatars
 * in its initial-data JSON, so this does a best-effort regex scrape of
 * the first avatar block and caches the image app-internally.
 *
 * Scheduled after every scan pass (see [ScanWorker]) — best-effort,
 * never blocks the feed: channels show their initials fallback until an
 * icon arrives, and a failed fetch is simply retried on the next scan.
 */
class ChannelIconWorker(
    appContext: Context,
    params: WorkerParameters,
    private val channelDao: ChannelDao,
) : CoroutineWorker(appContext, params) {

    private val iconCacheDir: File by lazy {
        File(applicationContext.cacheDir, "channel_icons").apply { mkdirs() }
    }

    override suspend fun doWork(): Result {
        val pending = channelDao.channelsWithoutIcon()
        if (pending.isEmpty()) return Result.success()

        Log.i(TAG, "Channel icon pass: ${pending.size} channel(s) without an icon")
        var fetched = 0
        var failed = 0

        for (channel in pending) {
            try {
                val path = withContext(Dispatchers.IO) { fetchAndStoreIcon(channel.id, channel.name) }
                if (path != null) {
                    channelDao.updateChannelIconPath(channel.id, path)
                    fetched++
                } else {
                    failed++
                }
            } catch (e: Exception) {
                failed++
                Log.e(TAG, "Channel icon fetch failed for '${channel.name}'", e)
            }
        }

        Log.i(TAG, "Channel icon pass done: $fetched fetched, $failed failed")
        // Always success: this is a best-effort enrichment pass, mirroring
        // ThumbnailFallbackWorker. Channels that failed stay icon-less and
        // are retried on the next scan (ScanWorker re-enqueues this pass).
        return Result.success()
    }

    /**
     * @return the cached icon path, or null when the avatar could not be
     * resolved (no search hit, network error). Never throws — callers
     * treat null as "skip this channel for now".
     */
    private suspend fun fetchAndStoreIcon(channelId: String, channelName: String): String? =
        withContext(Dispatchers.IO) {
            val cacheFile = File(iconCacheDir, "${sanitizeForFilename(channelId)}.jpg")
            if (cacheFile.exists()) return@withContext cacheFile.absolutePath

            val avatarUrl = resolveAvatarUrl(channelName) ?: return@withContext null
            val bytes = httpGet(avatarUrl) { it.readBytes() } ?: return@withContext null

            // The OS can clear the whole cache dir between runs — make
            // sure the parent exists right before writing.
            iconCacheDir.mkdirs()
            cacheFile.writeBytes(bytes)
            cacheFile.absolutePath
        }

    /**
     * Scrapes the search-result page for the first channel avatar. The
     * initial-data JSON orders thumbnails small → large, so the LAST url
     * inside the first "avatar" block is the highest-resolution one.
     */
    private fun resolveAvatarUrl(channelName: String): String? {
        val query = URLEncoder.encode(channelName, "UTF-8")
        val body = httpGet("$SEARCH_URL$query$CHANNELS_FILTER") {
            it.bufferedReader(Charsets.UTF_8).readText()
        } ?: return null

        val thumbnailsArray = AVATAR_BLOCK_REGEX.find(body)?.groupValues?.get(1) ?: return null
        return THUMBNAIL_URL_REGEX.findAll(thumbnailsArray)
            .lastOrNull()?.groupValues?.get(1)
            ?.unescapeJsonUrl()
    }

    /**
     * GETs [url] with a browser User-Agent (YouTube serves a JS-disabled
     * stub without one) and hands the response stream to [read]. Returns
     * null on any non-200 response or I/O failure — callers treat null as
     * "skip", never as an error to surface.
     */
    private fun <T> httpGet(url: String, read: (InputStream) -> T): T? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.i(TAG, "HTTP ${connection.responseCode} for channel icon GET")
                null
            } else {
                connection.inputStream.use(read)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Channel icon HTTP GET failed", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Channel ids are free-form strings ("UC..." or the scan engine's
     * "fname:..." heuristic id) — collapse anything outside [a-z0-9] to a
     * dash so the cache filename stays filesystem-safe.
     */
    private fun sanitizeForFilename(id: String): String =
        id.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "channel" }

    /** Minimal unescape for the URL escapes YouTube's inline JSON uses. */
    private fun String.unescapeJsonUrl(): String = this
        .replace("\\u003d", "=")
        .replace("\\u0026", "&")
        .replace("\\/", "/")

    companion object {
        const val UNIQUE_WORK_NAME = "localtube_channel_icons"
        private const val TAG = "LocalTubeScan"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val SEARCH_URL = "https://www.youtube.com/results?search_query="
        // &sp=EgIQAg%3D%3D is YouTube's "type: channel" search filter.
        private const val CHANNELS_FILTER = "&sp=EgIQAg%3D%3D"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000

        /** First "avatar":{"thumbnails":[...]} block on the results page, captured up to its closing bracket. */
        private val AVATAR_BLOCK_REGEX = Regex(
            "\"avatar\"\\s*:\\s*\\{\\s*\"thumbnails\"\\s*:\\s*\\[(.*?)]",
            RegexOption.DOT_MATCHES_ALL,
        )

        /** Each "url":"..." entry inside a captured thumbnails array. */
        private val THUMBNAIL_URL_REGEX = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
    }
}
