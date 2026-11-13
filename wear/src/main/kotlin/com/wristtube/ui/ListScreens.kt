package com.wristtube.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.Text
import coil3.compose.AsyncImage
import com.wristtube.data.Library
import com.wristtube.play.Playback
import com.wristtube.update.Updater
import com.wristtube.yt.Video
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun HomeScreen(
    update: Updater.Release?,
    onSearch: () -> Unit,
    onSearchFor: (String) -> Unit,
    onPlay: (Video) -> Unit,
    onNowPlaying: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onUpdate: () -> Unit,
) {
    val now by Playback.now.collectAsStateWithLifecycle()
    val searches by Library.searches.collectAsStateWithLifecycle()
    val history by Library.history.collectAsStateWithLifecycle()

    ListScreen { spec ->
        row(spec, "title") { ListHeader(modifier) { Text("WristTube") } }

        now?.let { np ->
            row(spec, "now") {
                Button(
                    onClick = onNowPlaying,
                    modifier = modifier,
                    transformation = transformation,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = {
                        AsyncImage(
                            model = np.video.thumbnail,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(32.dp).clip(CircleShape),
                        )
                    },
                    label = { Text(np.video.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = {
                        Text(
                            when {
                                np.error != null -> "Stopped"
                                np.loading -> "Loading..."
                                else -> "Now playing"
                            },
                            maxLines = 1,
                        )
                    },
                )
            }
        }

        row(spec, "search") {
            Button(
                onClick = onSearch,
                modifier = modifier,
                transformation = transformation,
                icon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                label = { Text("Search") },
                secondaryLabel = { Text("Speak or type", maxLines = 1) },
            )
        }

        if (update != null) row(spec, "update") {
            Button(
                onClick = onUpdate,
                modifier = modifier,
                transformation = transformation,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null) },
                label = { Text("Update available") },
                secondaryLabel = { Text(update.tag, maxLines = 1) },
            )
        }

        if (searches.isEmpty() && history.isEmpty() && now == null) {
            row(spec, "hint") {
                Caption("Search with your voice or the keyboard. Sound plays on the watch speaker; turn the crown for volume.")
            }
        }

        if (searches.isNotEmpty()) {
            row(spec, "recentHeader") { ListHeader(modifier) { Text("Recent searches") } }
            searches.take(4).forEach { q ->
                row(spec, "q:$q") {
                    Button(
                        onClick = { onSearchFor(q) },
                        modifier = modifier,
                        transformation = transformation,
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        icon = { Icon(Icons.Rounded.History, contentDescription = null) },
                        label = { Text(q, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }

        if (history.isNotEmpty()) {
            row(spec, "watchedHeader") { ListHeader(modifier) { Text("Watched") } }
            history.take(3).forEach { v -> row(spec, "h:${v.id}") { VideoCard(v, onClick = { onPlay(v) }) } }
            if (history.size > 3) row(spec, "allHistory") {
                Button(
                    onClick = onHistory,
                    modifier = modifier,
                    transformation = transformation,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    label = { Text("All history") },
                    secondaryLabel = { Text("${history.size} videos") },
                )
            }
        }

        row(spec, "settings") {
            Button(
                onClick = onSettings,
                modifier = modifier,
                transformation = transformation,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                label = { Text("Settings") },
            )
        }
    }
}

/** Search results, with more fetched as you near the end. */
@Composable
fun ResultsScreen(
    screen: Screen.Results,
    onPlay: (Video) -> Unit,
    onSearch: () -> Unit,
    onSearchFor: (String) -> Unit,
    onChannel: (Video) -> Unit,
) {
    val list = screen.list
    ListScreen(
        state = screen.scroll,
        edgeButton = {
            EdgeButton(onClick = onSearch, buttonSize = EdgeButtonSize.Medium) {
                Icon(Icons.Rounded.Mic, contentDescription = null, modifier = TinyIcon)
                Text(" Search", maxLines = 1)
            }
        },
    ) { spec ->
        row(spec, "title") {
            ListHeader(modifier) { Text(screen.query, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        list.suggestion?.let { s ->
            row(spec, "suggestion") {
                Button(
                    onClick = { onSearchFor(s) },
                    modifier = modifier,
                    transformation = transformation,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    label = { Text("Search for \"$s\"", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
        videoRows(spec, list, screen.scroll, firstIndex = if (list.suggestion != null) 2 else 1, onPlay,
            onLongClick = { v -> if (v.channelUrl != null) onChannel(v) })
    }
}

@Composable
fun ChannelScreen(screen: Screen.Channel, onPlay: (Video) -> Unit) {
    ListScreen(state = screen.scroll) { spec ->
        row(spec, "title") {
            ListHeader(modifier) { Text(screen.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        videoRows(spec, screen.list, screen.scroll, firstIndex = 1, onPlay, onLongClick = null)
    }
}

/**
 * The rows shared by every fetched list: videos, then a "More" button.
 *
 * Not an auto-loading spinner: a curved list keeps the item nearest the middle of the screen in
 * place when rows are inserted, so a spinner sitting there would stay in view as results landed
 * above it and fetch page after page. A button the user presses, followed by a jump to the first
 * new result, behaves predictably.
 */
private fun androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope.videoRows(
    spec: androidx.wear.compose.material3.lazy.TransformationSpec,
    list: Paged,
    state: androidx.wear.compose.foundation.lazy.TransformingLazyColumnState,
    firstIndex: Int,
    onPlay: (Video) -> Unit,
    onLongClick: ((Video) -> Unit)?,
) {
    if (list.videos.isEmpty() && list.error == null && !list.ended) {
        row(spec, "first") {
            LaunchedEffect(Unit) {
                list.more()
                state.requestScrollToItem(0)
            }
            Centered { CircularProgressIndicator(modifier = Modifier.size(32.dp)) }
        }
        return
    }
    list.videos.forEach { v ->
        row(spec, v.id) { VideoCard(v, onClick = { onPlay(v) }, onLongClick = onLongClick?.let { { it(v) } }) }
    }
    val error = list.error
    when {
        error != null -> {
            row(spec, "error") { Caption(error, androidx.wear.compose.material3.MaterialTheme.colorScheme.error) }
            row(spec, "retry") {
                val scope = rememberCoroutineScope()
                Button(
                    onClick = { list.retry(); scope.launch { list.more() } },
                    modifier = modifier,
                    transformation = transformation,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                    label = { Text("Try again") },
                )
            }
        }
        !list.ended -> row(spec, "more") {
            val scope = rememberCoroutineScope()
            Button(
                onClick = {
                    scope.launch {
                        val before = list.videos.size
                        list.more()
                        if (list.videos.size > before) state.requestScrollToItem(firstIndex + before)
                    }
                },
                enabled = !list.loading,
                modifier = modifier,
                transformation = transformation,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { if (list.loading) CircularProgressIndicator(modifier = Modifier.size(22.dp)) else Icon(Icons.Rounded.ExpandMore, contentDescription = null) },
                label = { Text(if (list.loading) "Loading..." else "More results") },
            )
        }
        list.videos.isEmpty() -> row(spec, "empty") { Caption("Nothing found.") }
    }
}

@Composable
fun HistoryScreen(onPlay: (Video) -> Unit, onChannel: (Video) -> Unit) {
    val history by Library.history.collectAsStateWithLifecycle()
    ListScreen { spec ->
        row(spec, "title") { ListHeader(modifier) { Text("Watched") } }
        if (history.isEmpty()) row(spec, "empty") { Caption("Nothing yet.") }
        history.forEach { v ->
            row(spec, v.id) {
                VideoCard(v, onClick = { onPlay(v) }, onLongClick = if (v.channelUrl != null) ({ onChannel(v) }) else null)
            }
        }
    }
}

/** What YouTube suggests after this video, and the channel's own list. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun UpNextScreen(onPlay: (Video) -> Unit, onChannel: (url: String, name: String) -> Unit) {
    val now by Playback.now.collectAsStateWithLifecycle()
    val streams = now?.streams
    ListScreen { spec ->
        row(spec, "title") { ListHeader(modifier) { Text("Up next") } }
        val video = streams?.video ?: now?.video
        if (video?.channelUrl != null) row(spec, "channel") {
            Button(
                onClick = { onChannel(video.channelUrl, video.channel) },
                modifier = modifier,
                transformation = transformation,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { Icon(Icons.Rounded.Person, contentDescription = null) },
                label = { Text(video.channel.ifBlank { "Channel" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = { Text("More from this channel", maxLines = 1) },
            )
        }
        val related = streams?.related.orEmpty()
        if (streams == null) row(spec, "wait") { Centered { CircularProgressIndicator(modifier = Modifier.size(28.dp)) } }
        else if (related.isEmpty()) row(spec, "none") { Caption("YouTube suggested nothing for this one.") }
        related.forEach { v -> row(spec, "r:${v.id}") { VideoCard(v, onClick = { onPlay(v) }) } }
    }
}

/** Play/pause glyph for the current state. */
fun playIcon(playing: Boolean) = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow
