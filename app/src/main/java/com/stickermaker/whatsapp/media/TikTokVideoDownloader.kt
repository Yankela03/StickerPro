package com.stickermaker.whatsapp.media

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

sealed class TikTokDownloadResult {
    data class Success(val file: File) : TikTokDownloadResult()
    data class Error(val message: String) : TikTokDownloadResult()
}

/** Downloads public videos from supported links when the source exposes a media URL. */
object TikTokVideoDownloader {
    private const val MAX_VIDEO_BYTES = 200L * 1024 * 1024
    private const val MAX_PAGE_BYTES = 5L * 1024 * 1024
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    init {
        // Maintain session cookies between page fetch and video download
        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))
        }
    }

    fun isTikTokUrl(value: String): Boolean = try {
        val uri = URI(value.trim())
        uri.scheme.equals("https", true) && isTikTokHost(uri.host)
    } catch (_: Exception) {
        false
    }

    suspend fun download(context: Context, value: String): TikTokDownloadResult = withContext(Dispatchers.IO) {
        if (!isTikTokUrl(value)) {
            return@withContext TikTokDownloadResult.Error("Enter a valid supported HTTPS video link.")
        }

        var output: File? = null
        try {
            val videoUrlStr = value.trim()
            val pageConnection = open(URL(videoUrlStr), referer = "https://www.tiktok.com/")
            val contentType = pageConnection.contentType.orEmpty().lowercase()
            val mediaUrl = if (contentType.startsWith("video/")) {
                pageConnection.url
            } else {
                val page = pageConnection.inputStream.use { input ->
                    val bytes = input.readNBytes((MAX_PAGE_BYTES + 1).toInt())
                    if (bytes.size > MAX_PAGE_BYTES) error("The video page was unexpectedly large.")
                    bytes.toString(Charsets.UTF_8)
                }
                pageConnection.disconnect()
                extractMediaUrl(page) ?: error(
                    "This video is private, restricted, or does not expose a downloadable copy."
                )
            }

            if (!isAllowedMediaUrl(mediaUrl)) error("The source returned an unsupported media address.")
            
            // For the actual video download, use the video page as referer
            val videoConnection = if (contentType.startsWith("video/")) pageConnection else open(mediaUrl, referer = videoUrlStr)
            val declaredSize = videoConnection.contentLengthLong
            if (declaredSize > MAX_VIDEO_BYTES) error("This online video is too large (maximum 200 MB).")
            val videoType = videoConnection.contentType.orEmpty().lowercase()
            if (!videoType.startsWith("video/") && videoType != "application/octet-stream") {
                error("The source did not return a downloadable video.")
            }

            val tempDir = File(context.cacheDir, "temp").apply { mkdirs() }
            output = File.createTempFile("online_video_", ".mp4", tempDir)
            videoConnection.inputStream.use { input ->
                output!!.outputStream().use { sink ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_VIDEO_BYTES) error("This online video is too large (maximum 200 MB).")
                        sink.write(buffer, 0, read)
                    }
                }
            }
            videoConnection.disconnect()
            if (output!!.length() == 0L) error("The source returned an empty video.")
            TikTokDownloadResult.Success(output!!)
        } catch (e: Exception) {
            output?.delete()
            TikTokDownloadResult.Error(e.message ?: "The online video could not be downloaded.")
        }
    }

    private fun open(url: URL, referer: String): HttpURLConnection = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,video/*;q=0.8,*/*;q=0.5")
        setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        setRequestProperty("Referer", referer)
        setRequestProperty("Cache-Control", "no-cache")
        setRequestProperty("Pragma", "no-cache")
        setRequestProperty("Sec-Fetch-Dest", if (url.toString().contains("video")) "video" else "document")
        setRequestProperty("Sec-Fetch-Mode", "navigate")
        setRequestProperty("Sec-Fetch-Site", "same-origin")
        connect()
        if (responseCode !in 200..299) error("The video source returned HTTP $responseCode.")
    }

    private fun extractMediaUrl(page: String): URL? {
        val keys = listOf("downloadAddr", "playAddr")
        for (key in keys) {
            val match = Regex("\\\"$key\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(page) ?: continue
            val decoded = decodeJsonUrl(match.groupValues[1])
            try {
                return URL(decoded)
            } catch (_: Exception) {
            }
        }
        
        // Alternative: look for url_list in JSON
        val urlListMatch = Regex("\\\"url_list\\\"\\s*:\\s*\\[\\\"([^\\\"]+)\\\"").find(page)
        if (urlListMatch != null) {
            val decoded = decodeJsonUrl(urlListMatch.groupValues[1])
            try {
                return URL(decoded)
            } catch (_: Exception) {
            }
        }

        return null
    }

    private fun decodeJsonUrl(value: String): String = value
        .replace("\\u002F", "/", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\/", "/")
        .replace("\\\\", "\\")

    private fun isTikTokHost(host: String?): Boolean {
        val normalized = host?.lowercase()?.trimEnd('.') ?: return false
        return normalized == "tiktok.com" || normalized.endsWith(".tiktok.com")
    }

    private fun isAllowedMediaUrl(url: URL): Boolean {
        if (url.protocol != "https") return false
        val host = url.host.lowercase().trimEnd('.')
        val suffixes = listOf("tiktok.com", "tiktokcdn.com", "tiktokv.com", "byteoversea.com", "ibytedtos.com", "musical.ly")
        return suffixes.any { host == it || host.endsWith(".$it") }
    }
}
