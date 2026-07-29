package com.gios.lightsports.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One OkHttp client for the whole app, plus a disk cache for the endpoints that
 * barely change (team lists, division names). Timeouts are short: this app is often
 * woken by an alarm inside a brief Doze allowlist window, and a socket that hangs
 * for thirty seconds there means the poll is killed before it returns.
 */
object Http {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun get(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            // ESPN's site API 403s a bare OkHttp user agent often enough to matter.
            .header("User-Agent", "Mozilla/5.0 (Android) LightSports")
            .header("Accept", "application/json, text/plain, */*")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    }.getOrNull()

    /**
     * Read-through file cache for slow-moving lists.
     * @param maxAgeMillis serve the file if it is younger than this, else refetch.
     */
    fun cached(dir: File, name: String, url: String, maxAgeMillis: Long): String? {
        val file = File(dir, name)
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < maxAgeMillis
        if (fresh) {
            runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val body = get(url)
        if (body != null) {
            runCatching { file.writeText(body) }
            return body
        }
        // Network failed: a stale list still beats an empty picker.
        return runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}
