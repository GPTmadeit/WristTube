package com.wristtube

import android.app.Application
import android.content.Context
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.wristtube.data.Library
import com.wristtube.data.Prefs
import com.wristtube.net.Http
import com.wristtube.net.NewPipeDownloader
import com.wristtube.play.Playback
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale

@UnstableApi
class App : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Library.init(this)
        // Results and titles in the watch's language, trending and search for its region.
        val locale = Locale.getDefault()
        NewPipe.init(
            NewPipeDownloader(Http.client),
            Localization.fromLocale(locale),
            ContentCountry(locale.country.ifBlank { "US" }),
        )
        Playback.init(this)
    }

    /** Thumbnails share the app's connection pool and keep a small disk cache. */
    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.client })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("thumbs"))
                .maxSizeBytes(24L * 1024 * 1024)
                .build()
        }
        .crossfade(false)
        .build()
}
