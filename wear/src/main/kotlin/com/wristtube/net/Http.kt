package com.wristtube.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The one HTTP client: YouTube's API (through NewPipe Extractor), thumbnails (Coil) and the media
 * itself (ExoPlayer) all share its connection pool, so a watch on power-saving Wi-Fi pays for each
 * TLS handshake once.
 */
object Http {

    /** What YouTube sees for page and media requests. The extractor sets its own per client. */
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
