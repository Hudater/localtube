package dev.hudater.localtube.core.data.scan

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.hudater.localtube.core.data.db.ChannelDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Background pass that fetches a channel avatar for channels indexed
 * without one (bare yt-dlp downloads only know the channel name).
 *
 * A single scrape of YouTube's search page turned out to be too fragile
 * in practice (consent.google.com interstitials, anti-bot stubs, changed
 * markup), so this tries a sequence of strategies per channel until one
 * yields an avatar URL:
 *
 *  1. **yt-search scrape** — the desktop `results?...&sp=<channels filter>`
 *     page with anti-consent cookies, regexed for the first
 *     `"avatar":{"thumbnails":[...]}` block (falling back to the first
 *     `yt3.*` avatar-host URL on the page).
 *  2. **Invidious JSON API** — `/api/v1/search?q=…&type=channel` on a few
 *     public instances; first hit's `authorThumbnails`, largest first.
 *  3. **Piped JSON API** — `/search?q=…&filter=channels` on a few public
 *     instances; first channel item's `thumbnail` (un-proxied when the
 *     instance exposes the origin via `?host=`).
 *
 * The resolved URL's bytes are cached app-internally. Scheduled after
 * every scan pass (see [ScanWorker]) — best-effort, never blocks the
 * feed: channels show their initials fallback until an icon arrives, and
 * a failed fetch is simply retried on the next scan. The whole pass is
 * wall-time capped so a run of dead instances can't hang the worker.
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
        val deadlineMs = System.currentTimeMillis() + OVERALL_BUDGET_MS
        var fetched = 0
        var failed = 0

        for (channel in pending) {
            if (System.currentTimeMillis() >= deadlineMs) {
                val remaining = pending.size - fetched - failed
                Log.w(
                    TAG,
                    "Icon pass out of wall-time budget after ${OVERALL_BUDGET_MS}ms — " +
                        "$remaining channel(s) keep their letter fallback until the next scan",
                )
                break
            }
            try {
                val path = withContext(Dispatchers.IO) { fetchAndStoreIcon(channel.id, channel.name, deadlineMs) }
                if (path != null) {
                    channelDao.updateChannelIconPath(channel.id, path)
                    fetched++
                } else {
                    failed++
                }
            } catch (e: Exception) {
                failed++
                Log.e(TAG, "Unexpected failure fetching icon for '${channel.name}'", e)
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
     * resolved (no strategy produced a working URL). Never throws —
     * callers treat null as "skip this channel for now".
     */
    private suspend fun fetchAndStoreIcon(channelId: String, channelName: String, deadlineMs: Long): String? =
        withContext(Dispatchers.IO) {
            val cacheFile = File(iconCacheDir, "${sanitizeForFilename(channelId)}.jpg")
            if (cacheFile.exists()) return@withContext cacheFile.absolutePath

            val avatarUrls = resolveAvatarUrls(channelName, deadlineMs)
            if (avatarUrls.isEmpty()) {
                Log.w(TAG, "All avatar strategies failed for '$channelName' — no URL found")
                return@withContext null
            }

            for (avatarUrl in avatarUrls) {
                val bytes = downloadIcon(avatarUrl, deadlineMs) ?: continue
                if (!looksLikeImage(bytes)) {
                    Log.w(
                        TAG,
                        "Avatar URL returned non-image bytes for '$channelName' " +
                            "(${bytes.size} bytes, head=${asciiHead(bytes)}) — $avatarUrl",
                    )
                    continue
                }
                // The OS can clear the whole cache dir between runs — make
                // sure the parent exists right before writing.
                iconCacheDir.mkdirs()
                cacheFile.writeBytes(bytes)
                Log.i(TAG, "Saved icon for '$channelName' (${bytes.size} bytes) from $avatarUrl")
                return@withContext cacheFile.absolutePath
            }
            Log.w(TAG, "Avatar URL(s) found for '$channelName' but none downloaded: $avatarUrls")
            null
        }

    /**
     * Runs every strategy in order until one produces at least one
     * downloadable URL. Returns candidates best-first (the first entry is
     * downloaded first); an empty list means "no strategy worked".
     */
    private fun resolveAvatarUrls(channelName: String, deadlineMs: Long): List<String> {
        val strategies: List<Pair<String, (String, Long) -> List<String>>> = listOf(
            "yt-search scrape" to { name, deadline -> searchPageAvatarUrls(name, deadline) },
            "invidious api" to { name, deadline -> invidiousAvatarUrls(name, deadline) },
            "piped api" to { name, deadline -> pipedAvatarUrls(name, deadline) },
        )
        for ((name, strategy) in strategies) {
            if (System.currentTimeMillis() >= deadlineMs) {
                Log.w(TAG, "Wall-time budget exhausted before [$name] for '$channelName'")
                return emptyList()
            }
            val urls = strategy(channelName, deadlineMs)
            if (urls.isNotEmpty()) {
                Log.i(TAG, "Avatar for '$channelName' resolved by [$name]: ${urls.first()}")
                return urls
            }
            Log.i(TAG, "[$name] found nothing for '$channelName'")
        }
        return emptyList()
    }

    // -----------------------------------------------------------------
    // Strategy 1: YouTube search-page scrape with anti-consent cookies
    // -----------------------------------------------------------------

    /**
     * Scrapes the channels-filter search-result page for the first
     * channel avatar. Sends consent/SOCS cookies so YouTube serves the
     * real initial-data JSON instead of the consent.google.com
     * interstitial, and a browser UA so it isn't a JS-disabled stub.
     */
    private fun searchPageAvatarUrls(channelName: String, deadlineMs: Long): List<String> {
        val query = URLEncoder.encode(channelName, "UTF-8")
        val body = httpGetString(
            url = "$SEARCH_URL$query$CHANNELS_FILTER",
            label = "yt-search",
            headers = SEARCH_PAGE_HEADERS,
            maxBytes = MAX_PAGE_BYTES,
            deadlineMs = deadlineMs,
        ) ?: return emptyList()

        if (body.length < MIN_PLAUSIBLE_PAGE_BYTES ||
            body.contains("consent.google.com") ||
            body.contains("Before you continue")
        ) {
            Log.w(
                TAG,
                "yt-search: response looks like a consent/anti-bot stub " +
                    "(${body.length} chars) — snippet: ${snippet(body)}",
            )
            return emptyList()
        }

        // The initial-data JSON orders each avatar block's thumbnails
        // small → large, so the LAST url inside the FIRST "avatar" block
        // is the top result's highest-resolution avatar.
        AVATAR_BLOCK_REGEX.find(body)?.groupValues?.get(1)?.let { block ->
            THUMBNAIL_URL_REGEX.findAll(block)
                .lastOrNull()?.groupValues?.get(1)
                ?.let { url -> return listOf(url.unescapeJsonUrl()) }
        }
        // Fallback: the first avatar-host URL anywhere on the page is
        // almost always the top channel hit's avatar on a channels-filter
        // page.
        YT3_URL_REGEX.find(body)?.groupValues?.get(1)?.let { url ->
            Log.i(TAG, "yt-search: avatar block regex missed, using first yt3 URL fallback")
            return listOf(url.unescapeJsonUrl())
        }
        Log.w(
            TAG,
            "yt-search: page fetched (${body.length} chars) but no avatar block or yt3 URL matched — " +
                "snippet: ${snippet(body)}",
        )
        return emptyList()
    }

    // -----------------------------------------------------------------
    // Strategy 2: Invidious JSON API
    // -----------------------------------------------------------------

    /**
     * Queries public Invidious instances' `/api/v1/search?type=channel`
     * and returns the first result's largest `authorThumbnails` URL.
     * Instances rotate/go down often, so every entry is best-effort and
     * all failures just move on to the next instance.
     */
    private fun invidiousAvatarUrls(channelName: String, deadlineMs: Long): List<String> {
        val query = URLEncoder.encode(channelName, "UTF-8")
        for (instance in INVIDIOUS_INSTANCES) {
            val body = httpGetString(
                url = "https://$instance/api/v1/search?q=$query&type=channel",
                label = "invidious[$instance]",
                headers = JSON_HEADERS,
                maxBytes = MAX_JSON_BYTES,
                deadlineMs = deadlineMs,
            ) ?: continue
            parseInvidiousSearch(body, instance).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return emptyList()
    }

    private fun parseInvidiousSearch(body: String, instance: String): List<String> {
        val results = try {
            JSONArray(body)
        } catch (e: Exception) {
            Log.w(
                TAG,
                "invidious[$instance]: non-JSON response (${body.length} chars) — snippet: ${snippet(body)}",
            )
            return emptyList()
        }
        if (results.length() == 0) {
            Log.w(TAG, "invidious[$instance]: empty result array")
            return emptyList()
        }
        // Tolerate instances that interleave non-channel entries: scan the
        // first few results for one that carries authorThumbnails.
        for (i in 0 until minOf(results.length(), 5)) {
            val item = results.optJSONObject(i) ?: continue
            val thumbs = item.optJSONArray("authorThumbnails") ?: continue
            var bestUrl: String? = null
            var bestWidth = -1
            for (j in 0 until thumbs.length()) {
                val thumb = thumbs.optJSONObject(j) ?: continue
                val url = thumb.optString("url")
                if (url.isEmpty()) continue
                val width = thumb.optInt("width", -1)
                if (bestUrl == null || width > bestWidth) {
                    bestUrl = url
                    bestWidth = width
                }
            }
            val raw = bestUrl ?: continue
            val normalized = normalizeInvidiousThumbUrl(raw, instance)
            Log.i(
                TAG,
                "invidious[$instance]: '${item.optString("author")}' avatar (width=$bestWidth) → $normalized",
            )
            return listOf(normalized)
        }
        Log.w(
            TAG,
            "invidious[$instance]: ${results.length()} result(s) but none with authorThumbnails — " +
                "snippet: ${snippet(body)}",
        )
        return emptyList()
    }

    /** Invidious thumbnails may be protocol-relative or instance-relative. */
    private fun normalizeInvidiousThumbUrl(url: String, instance: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("/") -> "https://$instance$url"
        else -> "https://$url"
    }

    // -----------------------------------------------------------------
    // Strategy 3: Piped JSON API
    // -----------------------------------------------------------------

    /**
     * Queries public Piped API instances for channel search hits. The
     * working filter value on current backends is `channels` (verified
     * 2026); `channel_list` is the historical variant kept as fallback.
     */
    private fun pipedAvatarUrls(channelName: String, deadlineMs: Long): List<String> {
        val query = URLEncoder.encode(channelName, "UTF-8")
        for (instance in PIPED_INSTANCES) {
            for (filter in PIPED_CHANNEL_FILTERS) {
                val body = httpGetString(
                    url = "https://$instance/search?q=$query&filter=$filter",
                    label = "piped[$instance/$filter]",
                    headers = JSON_HEADERS,
                    maxBytes = MAX_JSON_BYTES,
                    deadlineMs = deadlineMs,
                ) ?: continue
                parsePipedSearch(body, instance, filter).takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return emptyList()
    }

    private fun parsePipedSearch(body: String, instance: String, filter: String): List<String> {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            Log.w(
                TAG,
                "piped[$instance/$filter]: non-JSON response (${body.length} chars) — snippet: ${snippet(body)}",
            )
            return emptyList()
        }
        val items = root.optJSONArray("items")
        if (items == null) {
            Log.w(TAG, "piped[$instance/$filter]: JSON without \"items\" — snippet: ${snippet(body)}")
            return emptyList()
        }
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optString("type", "channel") != "channel") continue
            val thumbnail = item.optString("thumbnail")
            if (thumbnail.isEmpty()) continue

            val candidates = mutableListOf<String>()
            // Piped hands thumbnails out through its image proxy as
            // https://<proxy>/<path>?host=<origin> — the reconstructed
            // direct yt3 URL downloads without depending on that proxy,
            // so try it first and keep the proxy URL as fallback.
            directUrlFromPipedProxy(thumbnail)?.let { candidates.add(it) }
            candidates.add(thumbnail)
            Log.i(TAG, "piped[$instance/$filter]: '${item.optString("name")}' → $candidates")
            return candidates
        }
        Log.w(TAG, "piped[$instance/$filter]: no channel item with a thumbnail (${items.length()} items)")
        return emptyList()
    }

    /**
     * Turns `https://<proxy>/<path>?host=yt3.ggpht.com` into
     * `https://yt3.ggpht.com/<path>`, or null when the URL doesn't follow
     * that shape.
     */
    private fun directUrlFromPipedProxy(proxyUrl: String): String? {
        val marker = "?host="
        val markerIndex = proxyUrl.indexOf(marker)
        if (markerIndex < 0) return null
        val origin = URLDecoder.decode(
            proxyUrl.substring(markerIndex + marker.length).substringBefore('&'),
            "UTF-8",
        )
        val withoutScheme = proxyUrl.substring(0, markerIndex)
            .removePrefix("https://")
            .removePrefix("http://")
        val pathStart = withoutScheme.indexOf('/')
        if (origin.isEmpty() || pathStart < 0) return null
        return "https://$origin${withoutScheme.substring(pathStart)}"
    }

    // -----------------------------------------------------------------
    // Download + persistence
    // -----------------------------------------------------------------

    private fun downloadIcon(url: String, deadlineMs: Long): ByteArray? = httpGetBytes(
        url = url,
        label = "download",
        headers = IMAGE_HEADERS,
        maxBytes = MAX_ICON_BYTES,
        deadlineMs = deadlineMs,
    )

    /**
     * Channel ids are free-form strings ("UC..." or the scan engine's
     * "fname:..." heuristic id) — collapse anything outside [a-z0-9] to a
     * dash so the cache filename stays filesystem-safe.
     */
    private fun sanitizeForFilename(id: String): String =
        id.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "channel" }

    /**
     * Rejects HTML/error bodies masquerading as .jpg — a corrupt file
     * here would be cached forever (the cache check is existence-based).
     */
    private fun looksLikeImage(bytes: ByteArray): Boolean = when {
        bytes.size < 64 -> false
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> true // JPEG
        bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> true // PNG
        bytes.size > 12 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> true // WebP (RIFF....WEBP)
        bytes.size > 6 && String(bytes, 0, 3, Charsets.US_ASCII) == "GIF" -> true
        else -> false
    }

    // -----------------------------------------------------------------
    // HTTP plumbing (java.net only), all call sites run on Dispatchers.IO
    // -----------------------------------------------------------------

    /**
     * GETs [url] and returns the body as text (size-capped), or null on
     * any non-200 response or I/O failure — callers treat null as
     * "strategy failed", never as an error to surface.
     */
    private fun httpGetString(
        url: String,
        label: String,
        headers: Map<String, String>,
        maxBytes: Int,
        deadlineMs: Long,
    ): String? = httpGetBytes(url, label, headers, maxBytes, deadlineMs)?.let { String(it, Charsets.UTF_8) }

    /**
     * Blocking GET returning the (size-capped) response body, or null on
     * any failure. Every outcome is logged with [label] so the failing
     * strategy, host and HTTP code are identifiable in logcat. The
     * wall-time budget is re-checked here so a single request can never
     * start past the deadline.
     */
    private fun httpGetBytes(
        url: String,
        label: String,
        headers: Map<String, String>,
        maxBytes: Int,
        deadlineMs: Long,
    ): ByteArray? {
        if (System.currentTimeMillis() >= deadlineMs) {
            Log.w(TAG, "$label: skipping, wall-time budget (${OVERALL_BUDGET_MS}ms) exhausted")
            return null
        }
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "$label: HTTP $code for $url")
                null
            } else {
                readCapped(connection.inputStream, maxBytes).also { bytes ->
                    Log.i(TAG, "$label: 200 OK, ${bytes.size} bytes from $url")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "$label: GET failed for $url — ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Reads up to [maxBytes] from [stream] so a huge body can't blow the heap. */
    private fun readCapped(stream: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        while (out.size() < maxBytes) {
            val read = stream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** Minimal unescape for the URL escapes YouTube's inline JSON uses. */
    private fun String.unescapeJsonUrl(): String = this
        .replace("\\u003d", "=")
        .replace("\\u0026", "&")
        .replace("\\/", "/")

    /** Compact one-line body excerpt for logcat diagnosis. */
    private fun snippet(body: String): String =
        body.take(SNIPPET_CHARS).replace(Regex("\\s+"), " ").trim()

    /** First bytes as printable ASCII, for logging binary responses. */
    private fun asciiHead(bytes: ByteArray): String =
        String(bytes, 0, minOf(bytes.size, 64), Charsets.US_ASCII)
            .replace(Regex("[^\\x20-\\x7e]"), ".")

    companion object {
        const val UNIQUE_WORK_NAME = "localtube_channel_icons"
        private const val TAG = "LocalTubeScan"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private const val SEARCH_URL = "https://www.youtube.com/results?search_query="
        // &sp=EgIQAg%3D%3D is YouTube's "type: channel" search filter.
        private const val CHANNELS_FILTER = "&sp=EgIQAg%3D%3D"

        // Consent flags that make youtube.com skip the consent.google.com
        // interstitial for non-interactive (no-JS) requests.
        private const val CONSENT_COOKIES =
            "CONSENT=YES+cb.20210328-17-p0.en+FX+419; SOCS=CAISAiAD"

        private val SEARCH_PAGE_HEADERS = mapOf(
            "Cookie" to CONSENT_COOKIES,
            "Accept-Language" to "en-US,en;q=0.9",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )
        private val JSON_HEADERS = mapOf(
            "Accept" to "application/json",
            "Accept-Language" to "en-US,en;q=0.9",
        )
        private val IMAGE_HEADERS = mapOf(
            // Deliberately no image/webp — yt3 serves JPEG by default, matching the .jpg cache name.
            "Accept" to "image/*,*/*;q=0.8",
        )

        // Public frontends hit when the YouTube scrape fails, from the
        // official api.invidious.io / Piped instance lists (verified alive
        // Sept 2026). They rotate often, so each entry is best-effort.
        private val INVIDIOUS_INSTANCES = listOf(
            "inv.nadeko.net",
            "invidious.nerdvpn.de",
            "invidious.tiekoetter.com",
        )
        private val PIPED_INSTANCES = listOf(
            "pipedapi.ducks.party",
            "api.piped.private.coffee",
            "pipedapi.kavin.rocks",
        )
        private val PIPED_CHANNEL_FILTERS = listOf("channels", "channel_list")

        // Per-request budgets stay in the ~8-10s range; the whole pass is
        // additionally wall-time capped so a run of dead instances can't
        // hang the worker — anything past the deadline retries next scan.
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 10_000
        private const val OVERALL_BUDGET_MS = 45_000

        private const val MAX_PAGE_BYTES = 4 * 1024 * 1024
        private const val MAX_JSON_BYTES = 2 * 1024 * 1024
        private const val MAX_ICON_BYTES = 5 * 1024 * 1024
        private const val MIN_PLAUSIBLE_PAGE_BYTES = 50_000
        private const val SNIPPET_CHARS = 300

        /** First "avatar":{"thumbnails":[...]} block on the results page, captured up to its closing bracket. */
        private val AVATAR_BLOCK_REGEX = Regex(
            "\"avatar\"\\s*:\\s*\\{\\s*\"thumbnails\"\\s*:\\s*\\[(.*?)\\]",
            RegexOption.DOT_MATCHES_ALL,
        )

        /** Each "url":"..." entry inside a captured thumbnails array. */
        private val THUMBNAIL_URL_REGEX = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")

        /** Avatar-host URLs (yt3.ggpht.com / yt3.googleusercontent.com) anywhere in a page. */
        private val YT3_URL_REGEX = Regex("\"(https://yt3\\.(?:googleusercontent|ggpht)\\.com/[^\"]+)\"")
    }
}
