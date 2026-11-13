package com.wristtube

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.media3.common.util.UnstableApi
import com.wristtube.data.Library
import com.wristtube.net.WatchNetwork
import com.wristtube.play.Playback
import com.wristtube.ui.Screen
import com.wristtube.ui.WristTubeApp
import com.wristtube.update.UpdateController
import com.wristtube.update.Updater
import com.wristtube.yt.Video
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

@UnstableApi
class MainActivity : ComponentActivity() {

    /** Screens asked for from outside (the media notification, test intents). */
    private val requests = Channel<Screen>(Channel.BUFFERED)

    private val updates: UpdateController by lazy {
        UpdateController(this, BuildConfig.VERSION_NAME, Updater.ASSET_WATCH)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WristTubeApp(requests.receiveAsFlow(), updates) }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    // While the app is on screen, ask for Wi-Fi/LTE: browsing thumbnails over the phone's
    // Bluetooth link is slow too.
    override fun onStart() {
        super.onStart()
        WatchNetwork.acquire(this, "screen")
    }

    override fun onStop() {
        super.onStop()
        WatchNetwork.release("screen")
    }

    /**
     * ACTION_NOW_PLAYING comes from the media notification. The two extras make the app testable
     * over ADB without typing on a watch:
     *   adb shell am start -n com.wristtube/.MainActivity --es com.wristtube.SEARCH "lofi"
     *   adb shell am start -n com.wristtube/.MainActivity --es com.wristtube.PLAY <url or video id>
     */
    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_SEARCH)?.trim()?.takeIf { it.isNotEmpty() }?.let { q ->
            Library.searched(q)
            requests.trySend(Screen.Results(q))
        }
        intent.getStringExtra(EXTRA_PLAY)?.trim()?.takeIf { it.isNotEmpty() }?.let { target ->
            val url = if (target.startsWith("http")) target else "https://www.youtube.com/watch?v=$target"
            Playback.play(Video(url = url, title = "Loading..."))
            requests.trySend(Screen.Player)
        }
        if (intent.action == ACTION_NOW_PLAYING && Playback.now.value != null) requests.trySend(Screen.Player)
        intent.removeExtra(EXTRA_SEARCH)
        intent.removeExtra(EXTRA_PLAY)
    }

    companion object {
        const val ACTION_NOW_PLAYING = "com.wristtube.NOW_PLAYING"
        const val EXTRA_SEARCH = "com.wristtube.SEARCH"
        const val EXTRA_PLAY = "com.wristtube.PLAY"
    }
}
