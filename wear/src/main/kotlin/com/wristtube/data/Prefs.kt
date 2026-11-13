package com.wristtube.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import com.wristtube.yt.Video
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Settings, as one immutable snapshot the UI can observe. */
@Immutable
data class Settings(
    /** Tallest video to fetch. A watch screen is ~450 px across, so 240p already fills it. */
    val maxHeight: Int = 240,
    /** Start every video as sound only (the screen can sleep while it plays). */
    val audioOnly: Boolean = false,
    /** Always the watch speaker, even with headphones connected. Off: headphones when connected. */
    val speakerOnly: Boolean = true,
    /** 48 kbps audio instead of 128 kbps. */
    val dataSaver: Boolean = false,
    /** Play the first related video when one ends. */
    val autoplay: Boolean = false,
    /** How video fills the round screen: 0 inside the circle, 1 full width, 2 fill (cropped). */
    val zoom: Int = 0,
)

object Prefs {
    private lateinit var sp: SharedPreferences
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings
    val now: Settings get() = _settings.value

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _settings.value = Settings(
            maxHeight = sp.getInt("maxHeight", 240),
            audioOnly = sp.getBoolean("audioOnly", false),
            speakerOnly = sp.getBoolean("speakerOnly", true),
            dataSaver = sp.getBoolean("dataSaver", false),
            autoplay = sp.getBoolean("autoplay", false),
            zoom = sp.getInt("zoom", 0),
        )
    }

    /** One-off markers ("asked for notifications"), kept apart from the settings. */
    fun flag(name: String): Boolean = sp.getBoolean("flag.$name", false)

    fun setFlag(name: String) = sp.edit().putBoolean("flag.$name", true).apply()

    fun update(change: (Settings) -> Settings) {
        val s = change(_settings.value)
        _settings.value = s
        sp.edit()
            .putInt("maxHeight", s.maxHeight)
            .putBoolean("audioOnly", s.audioOnly)
            .putBoolean("speakerOnly", s.speakerOnly)
            .putBoolean("dataSaver", s.dataSaver)
            .putBoolean("autoplay", s.autoplay)
            .putInt("zoom", s.zoom)
            .apply()
    }
}

/** What you watched and searched for, newest first. Only on the watch. */
object Library {
    private const val MAX_HISTORY = 60
    private const val MAX_SEARCHES = 12

    private lateinit var sp: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }

    private val _history = MutableStateFlow<List<Video>>(emptyList())
    val history: StateFlow<List<Video>> = _history

    private val _searches = MutableStateFlow<List<String>>(emptyList())
    val searches: StateFlow<List<String>> = _searches

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("library", Context.MODE_PRIVATE)
        _history.value = runCatching {
            json.decodeFromString(ListSerializer(Video.serializer()), sp.getString("history", "[]")!!)
        }.getOrDefault(emptyList())
        _searches.value = runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), sp.getString("searches", "[]")!!)
        }.getOrDefault(emptyList())
    }

    fun watched(video: Video) {
        val list = listOf(video) + _history.value.filter { it.id != video.id }
        _history.value = list.take(MAX_HISTORY)
        sp.edit().putString("history", json.encodeToString(ListSerializer(Video.serializer()), _history.value)).apply()
    }

    fun searched(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val list = listOf(q) + _searches.value.filter { !it.equals(q, ignoreCase = true) }
        _searches.value = list.take(MAX_SEARCHES)
        sp.edit().putString("searches", json.encodeToString(ListSerializer(String.serializer()), _searches.value)).apply()
    }

    fun clear() {
        _history.value = emptyList()
        _searches.value = emptyList()
        sp.edit().clear().apply()
    }
}
