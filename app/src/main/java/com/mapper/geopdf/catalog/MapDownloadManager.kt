package com.mapper.geopdf.catalog

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.mapper.geopdf.geo.DistanceUnit
import com.mapper.geopdf.geo.GeoPdfDocumentInfo
import com.mapper.geopdf.geo.GeoPdfParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

data class ActiveDownload(
    val mapId: String,
    val mapName: String,
    val fileName: String,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val isAutomatic: Boolean
) {
    val progressPercent: Int?
        get() = totalBytes?.takeIf { it > 0L }?.let {
            ((bytesDownloaded * 100L) / it).toInt().coerceIn(0, 100)
        }
}

data class AutoCacheStats(
    val autoCachedBytes: Long,
    val autoCachedCount: Int,
    val pinnedBytes: Long,
    val partialBytes: Long,
    val partialCount: Int,
    val maxAutoBytes: Long
)

sealed class DownloadOutcome {
    data class Success(
        val entry: CatalogMapEntry,
        val parsedInfo: GeoPdfDocumentInfo,
        val evictedFiles: List<File> = emptyList()
    ) : DownloadOutcome()
    data class Failed(val entry: CatalogMapEntry, val reason: String) : DownloadOutcome()
    data class Cancelled(val entry: CatalogMapEntry) : DownloadOutcome()
}

enum class NetworkEligibility {
    ALLOWED,
    NO_INTERNET,
    WIFI_REQUIRED
}

/**
 * Pure-JVM resumable HTTP PDF downloader and structural validator used by [MapDownloadManager].
 */
internal object PdfHttpDownloader {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 5

    fun downloadToPartFile(
        initialUrl: String,
        partFile: File,
        etagFile: File,
        expectedSizeBytes: Long?,
        catalogEtag: String?,
        isActiveCheck: () -> Boolean,
        onConnectionChanged: (HttpURLConnection?) -> Unit,
        onProgress: (bytesDownloaded: Long, totalBytes: Long?) -> Unit
    ) {
        val savedEtag = if (etagFile.exists()) etagFile.readText().trim().takeIf { it.isNotEmpty() } else null
        var existingBytes = if (partFile.exists()) partFile.length() else 0L

        if (partFile.exists() && catalogEtag != null && savedEtag != null && savedEtag != catalogEtag) {
            partFile.delete()
            existingBytes = 0L
        }
        if (expectedSizeBytes != null && existingBytes > expectedSizeBytes) {
            partFile.delete()
            existingBytes = 0L
        }

        if (expectedSizeBytes != null && existingBytes == expectedSizeBytes && existingBytes > 0L) {
            onProgress(existingBytes, expectedSizeBytes)
            return
        }

        if (existingBytes > 0L) {
            onProgress(existingBytes, expectedSizeBytes)
        }

        val ifRange = if (existingBytes > 0L) (savedEtag ?: catalogEtag) else null
        var conn = openFollowingRedirects(initialUrl, rangeStart = existingBytes, ifRangeEtag = ifRange)
        onConnectionChanged(conn)
        try {
            if (!isActiveCheck()) throw CancellationException("Download cancelled")

            var status = conn.responseCode
            val responseEtag = conn.getHeaderField("ETag")?.trim()?.takeIf { it.isNotEmpty() }

            // Restart from zero if range is unsatisfiable or upstream ETag changed across 206 resume
            val rangeRejected = (status == 416 && existingBytes > 0L)
            val etagMismatchOnPartial = (status == HttpURLConnection.HTTP_PARTIAL &&
                existingBytes > 0L &&
                savedEtag != null &&
                responseEtag != null &&
                responseEtag != savedEtag)

            if (rangeRejected || etagMismatchOnPartial) {
                onConnectionChanged(null)
                conn.disconnect()
                partFile.delete()
                existingBytes = 0L
                conn = openFollowingRedirects(initialUrl, rangeStart = 0L, ifRangeEtag = null)
                onConnectionChanged(conn)
                if (!isActiveCheck()) throw CancellationException("Download cancelled")
                status = conn.responseCode
            }

            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("HTTP $status")
            }

            val contentType = conn.contentType?.lowercase() ?: ""
            if (contentType.contains("text/html")) {
                throw IOException("Server returned HTML instead of PDF")
            }

            val appendMode = (status == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L)
            if (!appendMode && existingBytes > 0L) {
                existingBytes = 0L
            }

            val finalEtag = conn.getHeaderField("ETag")?.trim()?.takeIf { it.isNotEmpty() } ?: catalogEtag
            if (finalEtag != null) {
                etagFile.writeText(finalEtag)
            } else if (etagFile.exists()) {
                etagFile.delete()
            }

            val contentLen = conn.contentLengthLong
            val totalBytes = when {
                appendMode && contentLen > 0L -> existingBytes + contentLen
                !appendMode && contentLen > 0L -> contentLen
                else -> expectedSizeBytes
            }

            var downloaded = existingBytes
            var lastNotifyMs = 0L

            conn.inputStream.use { input ->
                FileOutputStream(partFile, appendMode).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (isActiveCheck()) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        if (now - lastNotifyMs >= 250L) {
                            lastNotifyMs = now
                            onProgress(downloaded, totalBytes)
                        }
                    }
                    output.flush()
                }
            }

            if (!isActiveCheck()) {
                throw CancellationException("Download cancelled")
            }

            // Fail on truncated stream so .part remains intact for HTTP Range resume
            if (totalBytes != null && totalBytes > 0L && downloaded < totalBytes) {
                throw IOException("Incomplete transfer ($downloaded / $totalBytes bytes)")
            }

            onProgress(downloaded, totalBytes ?: downloaded)
        } finally {
            onConnectionChanged(null)
            try {
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    fun openFollowingRedirects(
        startUrl: String,
        rangeStart: Long,
        ifRangeEtag: String? = null
    ): HttpURLConnection {
        var currentUrl = encodeUrlSpaces(startUrl)
        for (hop in 0 until MAX_REDIRECTS) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "GeoPdfMapperAndroid/1.0")
                if (rangeStart > 0L) {
                    setRequestProperty("Range", "bytes=$rangeStart-")
                    if (!ifRangeEtag.isNullOrBlank()) {
                        setRequestProperty("If-Range", ifRangeEtag)
                    }
                }
            }

            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IOException("Redirect without Location header")
                }
                val nextUri = URL(URL(currentUrl), encodeUrlSpaces(location))
                currentUrl = nextUri.toString()
                continue
            }
            return conn
        }
        throw IOException("Too many redirects")
    }

    fun encodeUrlSpaces(raw: String): String = raw.replace(" ", "%20")

    fun hasValidPdfEnvelope(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() < 128L) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val headBytes = ByteArray(min(len, 1024L).toInt())
                raf.seek(0L)
                raf.readFully(headBytes)
                val head = String(headBytes, Charsets.ISO_8859_1)
                if (!head.contains("%PDF-")) return false

                val tailSize = min(len, 4096L).toInt()
                val tailBytes = ByteArray(tailSize)
                raf.seek(len - tailSize)
                raf.readFully(tailBytes)
                val tail = String(tailBytes, Charsets.ISO_8859_1)
                tail.contains("%%EOF")
            }
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * Loads the bundled USFS Forest Series spatial catalog and manages resumable background
 * PDF downloads, GeoPDF validation, and LRU cache pruning for auto-downloaded maps.
 */
class MapDownloadManager(
    context: Context,
    private val mapsDirProvider: () -> File,
    private val activeFileProvider: () -> File? = { null }
) {
    companion object {
        private const val PREFS_NAME = "geo_pdf_catalog_prefs"
        private const val KEY_AUTO_DOWNLOAD = "auto_download_enabled"
        private const val KEY_WIFI_ONLY = "wifi_only_download"
        private const val KEY_DISTANCE_UNIT = "distance_unit"
        private const val KEY_CATALOG_PREFIX = "catalog_"
        private const val KEY_PINNED_PREFIX = "pinned_"
        private const val KEY_AUTO_PREFIX = "auto_"
        private const val KEY_LAST_USED_PREFIX = "last_used_"

        const val MAX_AUTO_CACHE_BYTES = 1500L * 1024L * 1024L // 1.5 GB
        private const val AUTO_RETRY_COOLDOWN_MS = 60_000L
        private const val STALE_PART_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobLock = Any()
    private var downloadJob: Job? = null
    @Volatile
    private var activeConnection: HttpURLConnection? = null
    @Volatile
    private var activeToken: Long = 0L
    private val cooldownUntilMs = ConcurrentHashMap<String, Long>()

    private val _activeDownload = MutableStateFlow<ActiveDownload?>(null)
    val activeDownload: StateFlow<ActiveDownload?> = _activeDownload.asStateFlow()

    private val _autoDownloadEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUTO_DOWNLOAD, true))
    val autoDownloadEnabled: StateFlow<Boolean> = _autoDownloadEnabled.asStateFlow()

    private val _wifiOnlyDownload = MutableStateFlow(prefs.getBoolean(KEY_WIFI_ONLY, false))
    val wifiOnlyDownload: StateFlow<Boolean> = _wifiOnlyDownload.asStateFlow()

    private val _distanceUnit = MutableStateFlow(
        DistanceUnit.fromPref(prefs.getString(KEY_DISTANCE_UNIT, null))
    )
    val distanceUnit: StateFlow<DistanceUnit> = _distanceUnit.asStateFlow()

    suspend fun loadCatalog(): ForestCatalog = withContext(Dispatchers.IO) {
        try {
            appContext.assets.open("forest_catalog.json").use { stream ->
                ForestCatalogParser.parse(stream)
            }
        } catch (_: Exception) {
            ForestCatalog(schemaVersion = 1, generatedAt = "", maps = emptyList())
        }
    }

    fun setAutoDownloadEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD, enabled).apply()
        _autoDownloadEnabled.value = enabled
        if (!enabled && _activeDownload.value?.isAutomatic == true) {
            cancelActiveDownload()
        }
    }

    fun setWifiOnlyDownload(wifiOnly: Boolean) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY, wifiOnly).apply()
        _wifiOnlyDownload.value = wifiOnly
        if (wifiOnly && _activeDownload.value?.isAutomatic == true && checkNetworkEligibility() == NetworkEligibility.WIFI_REQUIRED) {
            cancelActiveDownload()
        }
    }

    fun setDistanceUnit(unit: DistanceUnit) {
        prefs.edit().putString(KEY_DISTANCE_UNIT, unit.prefValue).apply()
        _distanceUnit.value = unit
    }

    fun checkNetworkEligibility(): NetworkEligibility {
        val network = connectivityManager.activeNetwork ?: return NetworkEligibility.NO_INTERNET
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return NetworkEligibility.NO_INTERNET
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (!hasInternet) return NetworkEligibility.NO_INTERNET

        if (_wifiOnlyDownload.value && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            return NetworkEligibility.WIFI_REQUIRED
        }
        return NetworkEligibility.ALLOWED
    }

    fun isInAutoCooldown(mapId: String): Boolean {
        val until = cooldownUntilMs[mapId] ?: return false
        return System.currentTimeMillis() < until
    }

    fun isDownloading(): Boolean = synchronized(jobLock) { downloadJob?.isActive == true }

    fun isDownloading(mapId: String): Boolean = synchronized(jobLock) {
        downloadJob?.isActive == true && _activeDownload.value?.mapId == mapId
    }

    fun markMapUsed(file: File) {
        val key = fileKey(file)
        prefs.edit().putLong("$KEY_LAST_USED_PREFIX$key", System.currentTimeMillis()).apply()
    }

    fun isMapPinned(file: File): Boolean {
        return prefs.getBoolean("$KEY_PINNED_PREFIX${fileKey(file)}", false)
    }

    fun isAutoDownloaded(file: File): Boolean {
        return prefs.getBoolean("$KEY_AUTO_PREFIX${fileKey(file)}", false)
    }

    fun isCacheManaged(file: File): Boolean {
        val key = fileKey(file)
        return prefs.getBoolean("$KEY_CATALOG_PREFIX$key", false) ||
            prefs.getBoolean("$KEY_AUTO_PREFIX$key", false)
    }

    fun isAutoEvictable(file: File): Boolean {
        return isCacheManaged(file) && !isMapPinned(file)
    }

    fun setMapPinned(file: File, pinned: Boolean) {
        val key = fileKey(file)
        val editor = prefs.edit().putBoolean("$KEY_PINNED_PREFIX$key", pinned)
        if (isCacheManaged(file)) {
            editor.putBoolean("$KEY_AUTO_PREFIX$key", !pinned)
        }
        editor.apply()
    }

    fun clearAutoMetadata(file: File) {
        val key = fileKey(file)
        prefs.edit()
            .remove("$KEY_CATALOG_PREFIX$key")
            .remove("$KEY_AUTO_PREFIX$key")
            .remove("$KEY_PINNED_PREFIX$key")
            .remove("$KEY_LAST_USED_PREFIX$key")
            .apply()
    }

    fun clearSidecarsFor(file: File) {
        val dir = file.parentFile ?: mapsDirProvider()
        File(dir, "${file.name}.part").delete()
        File(dir, "${file.name}.etag").delete()
    }

    fun targetFileFor(entry: CatalogMapEntry): File {
        val sanitized = entry.fileName
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .let { if (it.endsWith(".pdf", ignoreCase = true)) it else "$it.pdf" }
        return File(mapsDirProvider(), sanitized)
    }

    fun cancelActiveDownload() {
        val current: ActiveDownload?
        val prevJob: Job?
        val prevConn: HttpURLConnection?
        synchronized(jobLock) {
            activeToken++
            current = _activeDownload.value
            prevJob = downloadJob
            downloadJob = null
            prevConn = activeConnection
            activeConnection = null
            _activeDownload.value = null
        }
        prevJob?.cancel()
        disconnectAsync(prevConn)
        if (current != null) {
            cooldownUntilMs[current.mapId] = System.currentTimeMillis() + AUTO_RETRY_COOLDOWN_MS
        }
    }

    /**
     * Starts downloading [entry] in the background. Calls [onComplete] on the Main thread.
     * Automatic requests are ignored while busy; manual requests cleanly preempt an active
     * download and wait for the previous coroutine to unwind before reusing the file/state.
     */
    fun startDownload(
        entry: CatalogMapEntry,
        isAutomatic: Boolean,
        pinned: Boolean = !isAutomatic,
        onComplete: (DownloadOutcome) -> Unit
    ) {
        val myToken: Long
        val prevJob: Job?
        val prevConn: HttpURLConnection?

        synchronized(jobLock) {
            val busy = downloadJob?.isActive == true
            if (busy && _activeDownload.value?.mapId == entry.id) return
            if (busy && isAutomatic) return

            myToken = ++activeToken
            prevJob = downloadJob
            prevConn = activeConnection
            activeConnection = null
            downloadJob = null
        }

        prevJob?.cancel()
        disconnectAsync(prevConn)

        if (!isAutomatic) {
            cooldownUntilMs.remove(entry.id)
        }

        val targetFile = targetFileFor(entry)
        val partFile = File(mapsDirProvider(), "${targetFile.name}.part")
        val initialBytes = if (partFile.exists()) partFile.length() else 0L

        synchronized(jobLock) {
            if (activeToken != myToken) return
            _activeDownload.value = ActiveDownload(
                mapId = entry.id,
                mapName = entry.mapName,
                fileName = targetFile.name,
                bytesDownloaded = initialBytes.coerceAtMost(entry.fileSizeBytes ?: initialBytes),
                totalBytes = entry.fileSizeBytes,
                isAutomatic = isAutomatic
            )

            downloadJob = scope.launch {
                prevJob?.join()
                if (activeToken != myToken) return@launch

                val outcome = try {
                    downloadAndValidate(entry, targetFile, isAutomatic, pinned, myToken)
                } catch (_: CancellationException) {
                    DownloadOutcome.Cancelled(entry)
                } catch (e: Exception) {
                    DownloadOutcome.Failed(entry, e.message ?: "Download failed")
                }

                if (outcome is DownloadOutcome.Failed) {
                    cooldownUntilMs[entry.id] = System.currentTimeMillis() + AUTO_RETRY_COOLDOWN_MS
                } else if (outcome is DownloadOutcome.Success) {
                    cooldownUntilMs.remove(entry.id)
                }

                withContext(Dispatchers.Main + NonCancellable) {
                    val stillOwner = synchronized(jobLock) {
                        val owns = (activeToken == myToken)
                        if (owns) {
                            _activeDownload.value = null
                            downloadJob = null
                        }
                        owns
                    }
                    if (stillOwner) {
                        onComplete(outcome)
                    }
                }
            }
        }
    }

    fun getAutoCacheStats(): AutoCacheStats {
        val dir = mapsDirProvider()
        val files = dir.listFiles { f -> f.isFile }?.toList().orEmpty()
        var autoBytes = 0L
        var autoCount = 0
        var pinnedBytes = 0L
        var partialBytes = 0L
        var partialCount = 0

        for (file in files) {
            when {
                file.extension.equals("pdf", ignoreCase = true) -> {
                    if (isAutoEvictable(file)) {
                        autoBytes += file.length()
                        autoCount++
                    } else if (isCacheManaged(file) && isMapPinned(file)) {
                        pinnedBytes += file.length()
                    }
                }
                file.name.endsWith(".part", ignoreCase = true) -> {
                    partialBytes += file.length()
                    partialCount++
                }
            }
        }

        return AutoCacheStats(
            autoCachedBytes = autoBytes,
            autoCachedCount = autoCount,
            pinnedBytes = pinnedBytes,
            partialBytes = partialBytes,
            partialCount = partialCount,
            maxAutoBytes = MAX_AUTO_CACHE_BYTES
        )
    }

    suspend fun clearAutoAndPartialCache(): List<File> = withContext(Dispatchers.IO) {
        val dir = mapsDirProvider()
        val files = dir.listFiles { f -> f.isFile }?.toList().orEmpty()
        val activePath = activeFileProvider()?.absolutePath
        val activeDownloadingName = _activeDownload.value?.fileName?.lowercase()

        val deleted = mutableListOf<File>()
        for (file in files) {
            if (file.absolutePath == activePath) continue
            when {
                file.extension.equals("pdf", ignoreCase = true) && isAutoEvictable(file) -> {
                    if (file.delete()) {
                        clearAutoMetadata(file)
                        clearSidecarsFor(file)
                        deleted.add(file)
                    }
                }
                file.name.endsWith(".part", ignoreCase = true) || file.name.endsWith(".etag", ignoreCase = true) -> {
                    val basePdf = file.name.removeSuffix(".part").removeSuffix(".etag").lowercase()
                    if (!isDownloading() || basePdf != activeDownloadingName) {
                        file.delete()
                    }
                }
            }
        }
        deleted
    }

    private suspend fun downloadAndValidate(
        entry: CatalogMapEntry,
        targetFile: File,
        isAutomatic: Boolean,
        pinned: Boolean,
        token: Long
    ): DownloadOutcome {
        val dir = mapsDirProvider()
        if (!dir.exists()) dir.mkdirs()

        // Reuse already-complete local GeoPDF cleanly if present
        if (targetFile.exists() && PdfHttpDownloader.hasValidPdfEnvelope(targetFile)) {
            val existingParsed = GeoPdfParser.parse(targetFile)
            if (existingParsed != null) {
                recordDownloadedFile(targetFile, isAutomatic = isAutomatic, pinned = pinned)
                return DownloadOutcome.Success(entry, existingParsed)
            }
        }

        val partFile = File(dir, "${targetFile.name}.part")
        val etagFile = File(dir, "${targetFile.name}.etag")

        val candidateUrls = listOfNotNull(
            entry.resolvedUrl?.takeIf { it.isNotBlank() },
            entry.downloadUrl
        ).distinct()

        val scopeCtx = currentCoroutineContext()
        var lastError: String? = null
        for (candidateUrl in candidateUrls) {
            if (!scopeCtx.isActive || activeToken != token) {
                throw CancellationException("Download cancelled")
            }
            try {
                PdfHttpDownloader.downloadToPartFile(
                    initialUrl = candidateUrl,
                    partFile = partFile,
                    etagFile = etagFile,
                    expectedSizeBytes = entry.fileSizeBytes,
                    catalogEtag = entry.etag,
                    isActiveCheck = { scopeCtx.isActive && activeToken == token },
                    onConnectionChanged = { conn ->
                        activeConnection = conn
                        if (conn != null && (!scopeCtx.isActive || activeToken != token)) {
                            disconnectAsync(conn)
                        }
                    },
                    onProgress = { downloaded, total ->
                        if (activeToken == token) {
                            _activeDownload.value = ActiveDownload(
                                mapId = entry.id,
                                mapName = entry.mapName,
                                fileName = targetFile.name,
                                bytesDownloaded = downloaded,
                                totalBytes = total,
                                isAutomatic = isAutomatic
                            )
                        }
                    }
                )
                lastError = null
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!scopeCtx.isActive || activeToken != token) {
                    throw CancellationException("Download cancelled")
                }
                lastError = e.message ?: "HTTP error"
            }
        }

        if (lastError != null) {
            return DownloadOutcome.Failed(entry, lastError)
        }

        if (!PdfHttpDownloader.hasValidPdfEnvelope(partFile)) {
            partFile.delete()
            etagFile.delete()
            return DownloadOutcome.Failed(entry, "Downloaded file is corrupted or incomplete")
        }

        val parsed = GeoPdfParser.parse(partFile)
        if (parsed == null) {
            partFile.delete()
            etagFile.delete()
            return DownloadOutcome.Failed(entry, "Downloaded file is missing GeoPDF calibration metadata")
        }

        if (targetFile.exists()) {
            targetFile.delete()
        }
        if (!partFile.renameTo(targetFile)) {
            partFile.inputStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output, bufferSize = 256 * 1024)
                }
            }
            partFile.delete()
        }
        etagFile.delete()

        recordDownloadedFile(targetFile, isAutomatic = isAutomatic, pinned = pinned)
        val finalParsed = GeoPdfParser.parse(targetFile) ?: parsed.copy(file = targetFile)
        val evicted = evictOldAutoMapsIfNeeded(excludeFile = targetFile)
        return DownloadOutcome.Success(entry, finalParsed, evicted)
    }

    private fun disconnectAsync(conn: HttpURLConnection?) {
        if (conn == null) return
        scope.launch {
            try {
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun recordDownloadedFile(file: File, isAutomatic: Boolean, pinned: Boolean) {
        val key = fileKey(file)
        val existingPinned = prefs.getBoolean("$KEY_PINNED_PREFIX$key", false)
        val finalPinned = existingPinned || pinned || !isAutomatic
        val finalAuto = !finalPinned
        prefs.edit()
            .putBoolean("$KEY_CATALOG_PREFIX$key", true)
            .putBoolean("$KEY_AUTO_PREFIX$key", finalAuto)
            .putBoolean("$KEY_PINNED_PREFIX$key", finalPinned)
            .putLong("$KEY_LAST_USED_PREFIX$key", System.currentTimeMillis())
            .apply()
    }

    private fun evictOldAutoMapsIfNeeded(excludeFile: File): List<File> {
        val dir = mapsDirProvider()
        val files = dir.listFiles { f -> f.isFile }?.toList().orEmpty()
        var totalAutoBytes = 0L
        val evictCandidates = mutableListOf<Pair<File, Long>>()
        val now = System.currentTimeMillis()
        val excludeNameLower = excludeFile.name.lowercase()
        val activePath = activeFileProvider()?.absolutePath

        for (file in files) {
            when {
                file.extension.equals("pdf", ignoreCase = true) -> {
                    if (isAutoEvictable(file)) {
                        totalAutoBytes += file.length()
                        if (file.absolutePath != excludeFile.absolutePath && file.absolutePath != activePath) {
                            val lastUsed = prefs.getLong("$KEY_LAST_USED_PREFIX${fileKey(file)}", file.lastModified())
                            evictCandidates.add(file to lastUsed)
                        }
                    }
                }
                file.name.endsWith(".part", ignoreCase = true) || file.name.endsWith(".etag", ignoreCase = true) -> {
                    val basePdf = file.name.removeSuffix(".part").removeSuffix(".etag").lowercase()
                    if (basePdf != excludeNameLower && now - file.lastModified() > STALE_PART_MAX_AGE_MS) {
                        file.delete()
                    }
                }
            }
        }

        if (totalAutoBytes <= MAX_AUTO_CACHE_BYTES) return emptyList()

        val deleted = mutableListOf<File>()
        evictCandidates.sortBy { it.second }
        for ((file, _) in evictCandidates) {
            if (totalAutoBytes <= MAX_AUTO_CACHE_BYTES) break
            val len = file.length()
            if (file.delete()) {
                totalAutoBytes -= len
                clearAutoMetadata(file)
                clearSidecarsFor(file)
                deleted.add(file)
            }
        }
        return deleted
    }

    private fun fileKey(file: File): String = file.name.lowercase()
}
