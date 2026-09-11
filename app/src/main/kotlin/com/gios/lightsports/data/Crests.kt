package com.gios.lightsports.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * A team's crest for a notification, from what is already on the phone.
 *
 * The feed's loader ([com.gios.lightsports.ui.Logos]) will go to the network for a crest it
 * has not seen; this one never does. It runs on whatever thread a score arrived on — a
 * broadcast receiver, a websocket callback — and a card with no crest is a card that reads
 * perfectly well, so a miss is simply a miss.
 *
 * Two lookups, both cheap after the first: the league's team list is a file the picker
 * already caches for a week, and the crest is a file the feed already downloaded.
 */
object Crests {

    /** leagueId -> (teamId -> crest URL). Built once per process from the cached team list. */
    private val urls = mutableMapOf<String, Map<String, String>>()

    /** Decoded crests, by URL. A handful of 64px bitmaps; the map is never large. */
    private val bitmaps = mutableMapOf<String, Bitmap?>()

    /** The largest a crest is ever drawn beside a notification, in pixels. */
    private const val TARGET_PX = 96

    fun bitmap(context: Context, leagueId: String?, teamId: String?): Bitmap? {
        if (leagueId == null || teamId == null) return null
        val url = url(context, leagueId, teamId) ?: return null
        synchronized(bitmaps) {
            if (bitmaps.containsKey(url)) return bitmaps[url]
        }
        val decoded = decode(File(context.filesDir, "logos"), url)
        synchronized(bitmaps) { bitmaps[url] = decoded }
        return decoded
    }

    private fun url(context: Context, leagueId: String, teamId: String): String? {
        synchronized(urls) { urls[leagueId] }?.let { return it[teamId] }
        val league = Leagues.byId(leagueId) ?: return null
        val map = runCatching {
            SportsRepository(context).teams(league).mapNotNull { team ->
                team.logoUrl?.let { team.teamId to it }
            }.toMap()
        }.getOrDefault(emptyMap())
        synchronized(urls) { urls[leagueId] = map }
        return map[teamId]
    }

    /**
     * The cache file the feed's loader writes, read back without a network call.
     *
     * The name has to be derived the same way on both sides; it is here rather than shared
     * because the loader is a Compose file and this runs where Compose is not.
     */
    private fun decode(dir: File, url: String): Bitmap? {
        val name = url.substringAfterLast('/').take(24)
        val file = File(dir, "${url.hashCode().toUInt().toString(16)}-$name")
        if (!file.exists() || file.length() <= 0) return null
        return runCatching {
            val bytes = file.readBytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            var w = bounds.outWidth
            var h = bounds.outHeight
            while (w / 2 >= TARGET_PX && h / 2 >= TARGET_PX) {
                w /= 2; h /= 2; sample *= 2
            }
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }.getOrNull()
    }
}
