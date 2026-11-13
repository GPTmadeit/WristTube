package com.wristtube.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.wristtube.BuildConfig
import com.wristtube.data.Library
import com.wristtube.data.Prefs
import com.wristtube.net.WatchNetwork
import com.wristtube.play.Playback
import com.wristtube.update.UpdateController

private val QUALITIES = listOf(144, 240, 360, 480)
private val ZOOMS = listOf("Inside the circle", "Full width", "Fill the screen")

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SettingsScreen(updates: UpdateController, onUpdates: () -> Unit) {
    val s by Prefs.settings.collectAsStateWithLifecycle()
    val updateStatus by updates.status.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    ListScreen { spec ->
        row(spec, "title") { ListHeader(modifier) { Text("Settings") } }

        row(spec, "speaker") {
            SwitchButton(
                checked = s.speakerOnly,
                onCheckedChange = { on -> Prefs.update { it.copy(speakerOnly = on) }; Playback.applySettings() },
                modifier = modifier,
                transformation = transformation,
                label = { Text("Watch speaker") },
                secondaryLabel = { Text(if (s.speakerOnly) "Even with headphones on" else "Headphones when connected") },
            )
        }

        row(spec, "quality") {
            Button(
                onClick = {
                    val next = QUALITIES[(QUALITIES.indexOf(s.maxHeight) + 1) % QUALITIES.size]
                    Prefs.update { it.copy(maxHeight = next) }
                },
                modifier = modifier,
                transformation = transformation,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { Icon(Icons.Rounded.HighQuality, contentDescription = null) },
                label = { Text("Video quality") },
                secondaryLabel = { Text("Up to ${s.maxHeight}p (next video)") },
            )
        }

        row(spec, "zoom") {
            Button(
                onClick = { Prefs.update { it.copy(zoom = (it.zoom + 1) % ZOOMS.size) } },
                modifier = modifier,
                transformation = transformation,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { Icon(Icons.Rounded.ZoomOutMap, contentDescription = null) },
                label = { Text("Video size") },
                secondaryLabel = { Text("${ZOOMS[s.zoom.coerceIn(0, 2)]} · double-tap to change") },
            )
        }

        row(spec, "audioOnly") {
            SwitchButton(
                checked = s.audioOnly,
                onCheckedChange = { on -> Prefs.update { it.copy(audioOnly = on) } },
                modifier = modifier,
                transformation = transformation,
                label = { Text("Start as sound only") },
                secondaryLabel = { Text("The screen can sleep while it plays") },
            )
        }

        row(spec, "dataSaver") {
            SwitchButton(
                checked = s.dataSaver,
                onCheckedChange = { on -> Prefs.update { it.copy(dataSaver = on) } },
                modifier = modifier,
                transformation = transformation,
                label = { Text("Data saver audio") },
                secondaryLabel = { Text(if (s.dataSaver) "48 kbps" else "128 kbps") },
            )
        }

        row(spec, "autoplay") {
            SwitchButton(
                checked = s.autoplay,
                onCheckedChange = { on -> Prefs.update { it.copy(autoplay = on) } },
                modifier = modifier,
                transformation = transformation,
                label = { Text("Autoplay next") },
                secondaryLabel = { Text("YouTube's first suggestion") },
            )
        }

        row(spec, "clear") {
            Button(
                onClick = {
                    if (confirmClear) {
                        Library.clear()
                        confirmClear = false
                    } else {
                        confirmClear = true
                    }
                },
                modifier = modifier,
                transformation = transformation,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = null) },
                label = { Text(if (confirmClear) "Tap again to clear" else "Clear history") },
                secondaryLabel = { Text("Searches and watched videos") },
            )
        }

        row(spec, "updates") {
            Button(
                onClick = onUpdates,
                modifier = modifier,
                transformation = transformation,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null) },
                label = { Text("Updates") },
                secondaryLabel = { Text(updates.summary(updateStatus), maxLines = 2) },
            )
        }

        row(spec, "about") {
            Caption(
                "WristTube ${BuildConfig.VERSION_NAME}\n" +
                    "Network: ${WatchNetwork.label} · Sound: ${Playback.outputLabel()}\n" +
                    "Not made by or affiliated with YouTube or Google. Uses NewPipe Extractor (GPL-3.0).",
            )
        }
    }
}
