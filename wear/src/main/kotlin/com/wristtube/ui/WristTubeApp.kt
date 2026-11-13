package com.wristtube.ui

import android.app.RemoteInput
import android.content.Intent
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.SwipeToDismissBox
import androidx.wear.compose.material3.TimeText
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.input.wearableExtender
import com.wristtube.data.Library
import com.wristtube.play.Playback
import com.wristtube.update.UpdateController
import com.wristtube.update.Updater
import com.wristtube.yt.Video
import com.wristtube.yt.VideoPage
import com.wristtube.yt.YouTube
import com.wristtube.yt.YouTubeException
import kotlinx.coroutines.flow.Flow
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler

/**
 * A list fetched a page at a time. It lives in its [Screen], not in composition, so going into the
 * player and swiping back finds the list (and its scroll position) as it was.
 */
class Paged(private val fetch: suspend (Page?) -> VideoPage) {
    var videos by mutableStateOf(emptyList<Video>())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var suggestion by mutableStateOf<String?>(null)
        private set
    var ended by mutableStateOf(false)
        private set
    private var next: Page? = null
    private var started = false

    val canLoadMore: Boolean get() = !ended && error == null

    suspend fun more() {
        if (loading || ended || (started && next == null)) return
        loading = true
        error = null
        try {
            val page = fetch(next)
            started = true
            val seen = videos.mapTo(HashSet()) { it.id }
            videos = videos + page.videos.filter { seen.add(it.id) }
            next = page.next
            ended = page.next == null
            if (suggestion == null) suggestion = page.suggestion
        } catch (e: YouTubeException) {
            error = e.message
        } finally {
            loading = false
        }
    }

    fun retry() {
        error = null
    }
}

sealed interface Screen {
    data object Home : Screen
    data object Player : Screen
    data object UpNext : Screen
    data object History : Screen
    data object Settings : Screen
    data object Updates : Screen

    class Results(val query: String) : Screen {
        val list = Paged { page -> YouTube.search(query, page) }
        val scroll = TransformingLazyColumnState()
    }

    class Channel(val url: String, name: String) : Screen {
        var title by mutableStateOf(name)
        private var tab: ListLinkHandler? = null
        val list = Paged { page ->
            if (page == null) {
                val channel = YouTube.channel(url)
                tab = channel.tab
                title = channel.name
                channel.page
            } else {
                YouTube.channelMore(tab ?: throw YouTubeException("Channel has no videos"), page)
            }
        }
        val scroll = TransformingLazyColumnState()
    }
}

const val KEY_SEARCH = "wristtube_search"

/** The watch's own input picker: voice first, keyboard a swipe away. */
fun searchIntent(): Intent {
    val input = RemoteInput.Builder(KEY_SEARCH)
        .setLabel("Search YouTube")
        .wearableExtender {
            setEmojisAllowed(false)
            setInputActionType(EditorInfo.IME_ACTION_SEARCH)
        }
        .build()
    return RemoteInputIntentHelper.createActionRemoteInputIntent().also {
        RemoteInputIntentHelper.putRemoteInputsExtra(it, listOf(input))
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun WristTubeApp(requests: Flow<Screen>, updates: UpdateController) {
    val ctx = LocalContext.current
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    fun push(s: Screen) {
        if (stack.last() == s) return
        // Only one player and one up-next list: bring them forward rather than stacking copies.
        if (s == Screen.Player || s == Screen.UpNext) stack.remove(s)
        stack.add(s)
    }
    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
    fun play(video: Video) {
        Playback.play(video)
        push(Screen.Player)
    }

    LaunchedEffect(requests) { requests.collect { push(it) } }
    // A back key (or gesture) steps back through the app's own screens before leaving it.
    BackHandler(enabled = stack.size > 1) { pop() }
    LaunchedEffect(Unit) { updates.checkQuietly() }

    val search = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(KEY_SEARCH) }
            ?.toString()?.trim()
        if (!text.isNullOrBlank()) {
            Library.searched(text)
            push(Screen.Results(text))
        }
    }
    val launchSearch: () -> Unit = { runCatching { search.launch(searchIntent()) } }
    fun searchFor(q: String) {
        Library.searched(q)
        push(Screen.Results(q))
    }

    val updateStatus by updates.status.collectAsStateWithLifecycle()

    @Composable
    fun Render(screen: Screen) {
        when (screen) {
            Screen.Home -> HomeScreen(
                update = (updateStatus as? Updater.Status.Available)?.release,
                onSearch = launchSearch,
                onSearchFor = ::searchFor,
                onPlay = ::play,
                onNowPlaying = { push(Screen.Player) },
                onHistory = { push(Screen.History) },
                onSettings = { push(Screen.Settings) },
                onUpdate = { push(Screen.Updates) },
            )
            is Screen.Results -> ResultsScreen(screen, onPlay = ::play, onSearch = launchSearch, onSearchFor = ::searchFor,
                onChannel = { push(Screen.Channel(it.channelUrl!!, it.channel)) })
            is Screen.Channel -> ChannelScreen(screen, onPlay = ::play)
            Screen.History -> HistoryScreen(onPlay = ::play, onChannel = { push(Screen.Channel(it.channelUrl!!, it.channel)) })
            Screen.Player -> PlayerScreen(onUpNext = { push(Screen.UpNext) }, onDone = ::pop)
            Screen.UpNext -> UpNextScreen(
                onPlay = { Playback.play(it); push(Screen.Player) },
                onChannel = { url, name -> push(Screen.Channel(url, name)) },
            )
            Screen.Settings -> SettingsScreen(updates = updates, onUpdates = { updates.check(); push(Screen.Updates) })
            Screen.Updates -> UpdateScreen(
                status = updateStatus,
                currentVersion = updates.currentVersionLabel,
                canInstall = Updater.canRequestInstalls(ctx),
                onCheck = { updates.check() },
                onInstall = { updates.install(it) },
            )
        }
    }

    WristTubeTheme {
        AppScaffold(timeText = { if (stack.last() != Screen.Player) TimeText() }) {
            val current = stack.last()
            if (stack.size == 1) {
                Render(current)
            } else {
                SwipeToDismissBox(onDismissed = ::pop) { isBackground ->
                    Render(if (isBackground) stack[stack.lastIndex - 1] else current)
                }
            }
        }
    }
}
