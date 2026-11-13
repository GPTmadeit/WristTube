package com.wristtube.yt

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException
import kotlin.math.abs

/** A video in a list: search results, a channel, history. */
@Immutable
@Serializable
data class Video(
    val url: String,
    val title: String,
    val channel: String = "",
    val channelUrl: String? = null,
    val durationSec: Long = -1,
    val thumbnail: String? = null,
    val live: Boolean = false,
    val views: Long = -1,
    val uploaded: String? = null,
) {
    val id: String get() = url.substringAfter("v=", url).substringBefore('&')
}

/** One page of a list, and how to ask for the next. */
@Immutable
class VideoPage(val videos: List<Video>, val next: Page?, val suggestion: String? = null)

/** What playing a video needs: the media URLs this watch can use, and what to show around it. */
@Immutable
data class Streams(
    val video: Video,
    /** AAC (or failing that any) audio in the video's original language. */
    val audioUrl: String?,
    val audioKbps: Int,
    /** H.264 video only, no audio, at most the chosen height. */
    val videoUrl: String?,
    val videoHeight: Int,
    /** Live streams only play through HLS. */
    val hlsUrl: String?,
    val related: List<Video>,
)

/** What went wrong, in a sentence that fits on a watch. */
class YouTubeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * YouTube through NewPipe Extractor: no API key, no account. As of extractor 0.27 the stream URLs
 * come from YouTube's visionOS client, which needs no PoToken, so nothing here needs a web view
 * (which Wear OS doesn't have).
 */
object YouTube {

    private val service get() = ServiceList.YouTube

    suspend fun search(query: String, page: Page? = null): VideoPage = call {
        val handler = service.searchQHFactory.fromQuery(
            query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "",
        )
        if (page == null) {
            val info = org.schabi.newpipe.extractor.search.SearchInfo.getInfo(service, handler)
            VideoPage(info.relatedItems.toVideos(), info.nextPage, info.searchSuggestion?.takeIf { it.isNotBlank() })
        } else {
            val more = org.schabi.newpipe.extractor.search.SearchInfo.getMoreItems(service, handler, page)
            VideoPage(more.items.toVideos(), more.nextPage)
        }
    }

    /** A channel's videos tab. The handler of the tab is kept so later pages can be fetched. */
    class ChannelPage(val name: String, val avatar: String?, val tab: ListLinkHandler?, val page: VideoPage)

    suspend fun channel(url: String): ChannelPage = call {
        val info = ChannelInfo.getInfo(service, url)
        val tab = info.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters } ?: info.tabs.firstOrNull()
        val first = tab?.let { ChannelTabInfo.getInfo(service, it) }
        ChannelPage(
            name = info.name,
            avatar = info.avatars.pick(96),
            tab = tab,
            page = VideoPage(first?.relatedItems?.toVideos().orEmpty(), first?.nextPage),
        )
    }

    suspend fun channelMore(tab: ListLinkHandler, page: Page): VideoPage = call {
        val more = ChannelTabInfo.getMoreItems(service, tab, page)
        VideoPage(more.items.toVideos(), more.nextPage)
    }

    /**
     * Resolves a video to playable URLs. [maxHeight] caps the video (a watch screen is about 450 px
     * across, so 240p already fills it); [dataSaver] picks the 48 kbps audio over 128 kbps.
     */
    suspend fun streams(url: String, maxHeight: Int, dataSaver: Boolean): Streams = call {
        val info = StreamInfo.getInfo(service, url)
        val live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
        val audio = pickAudio(info.audioStreams, dataSaver)
        val video = pickVideo(info.videoOnlyStreams, maxHeight)
        val hls = info.hlsUrl?.takeIf { it.isNotBlank() }
        if (!live && audio == null) throw YouTubeException("YouTube sent no audio this watch can play")
        if (live && hls == null) throw YouTubeException("This live stream has no playable feed")
        Streams(
            video = Video(
                url = info.url,
                title = info.name,
                channel = info.uploaderName.orEmpty(),
                channelUrl = info.uploaderUrl?.takeIf { it.isNotBlank() },
                durationSec = info.duration,
                thumbnail = info.thumbnails.pick(),
                live = live,
                views = info.viewCount,
                uploaded = info.textualUploadDate,
            ),
            audioUrl = audio?.content,
            audioKbps = audio?.averageBitrate ?: 0,
            videoUrl = video?.content,
            videoHeight = video?.height ?: 0,
            hlsUrl = if (live) hls else null,
            related = info.relatedItems.toVideos(),
        )
    }

    // --- picking -----------------------------------------------------------------------------------

    /**
     * Audio in the original language (YouTube also offers dubbed and described tracks), AAC first:
     * the watch's software Opus decoder has misbehaved under load on Android 17 (see WristCord's
     * docs/wear-audio.md), and AAC is what every Android device decodes in hardware or not.
     */
    fun pickAudio(all: List<AudioStream>, dataSaver: Boolean): AudioStream? {
        val usable = all.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val original = usable.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
            .ifEmpty { usable }
        val pool = original.filter { it.format == MediaFormat.M4A }.ifEmpty { original }
        return if (dataSaver) pool.minByOrNull { it.averageBitrate } else pool.maxByOrNull { it.averageBitrate }
    }

    /** H.264 video only: hardware-decoded on every watch, unlike VP9 and AV1. */
    fun pickVideo(all: List<VideoStream>, maxHeight: Int): VideoStream? {
        val avc = all.filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                it.format == MediaFormat.MPEG_4 && it.codec.orEmpty().startsWith("avc1")
        }
        return avc.filter { it.height in 1..maxHeight }.maxByOrNull { it.height } ?: avc.minByOrNull { it.height }
    }

    // --- helpers -----------------------------------------------------------------------------------

    private fun List<InfoItem>.toVideos(): List<Video> = mapNotNull { item ->
        val s = item as? StreamInfoItem ?: return@mapNotNull null
        Video(
            url = s.url,
            title = s.name,
            channel = s.uploaderName.orEmpty(),
            channelUrl = s.uploaderUrl?.takeIf { it.isNotBlank() },
            durationSec = s.duration,
            thumbnail = s.thumbnails.pick(),
            live = s.streamType == StreamType.LIVE_STREAM || s.streamType == StreamType.AUDIO_LIVE_STREAM,
            views = s.viewCount,
            uploaded = s.textualUploadDate,
        )
    }

    /** The thumbnail closest to [width] pixels wide: big enough for the screen, no bigger. */
    private fun List<Image>.pick(width: Int = 320): String? =
        minByOrNull { abs((if (it.width > 0) it.width else 480) - width) }?.url

    private suspend fun <T> call(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: YouTubeException) {
            throw e
        } catch (e: Exception) {
            throw YouTubeException(friendly(e), e)
        }
    }

    fun friendly(e: Throwable): String = when (e) {
        is YouTubeException -> e.message ?: "Something went wrong"
        is AgeRestrictedContentException -> "Age-restricted. YouTube only shows it to signed-in accounts."
        is GeographicRestrictionException -> "Not available in your country."
        is PaidContentException -> "Members-only or paid video."
        is PrivateContentException -> "This video is private."
        is SignInConfirmNotBotException -> "YouTube wants this network to prove it isn't a bot. Try again later."
        is ReCaptchaException -> "YouTube is rate-limiting this network. Try again in a while."
        is ContentNotAvailableException -> e.message?.substringAfter(": \"")?.removeSuffix("\"")
            ?.takeIf { it.isNotBlank() && it.length < 120 } ?: "Video unavailable."
        is IOException -> "Couldn't reach YouTube. Check the watch's connection."
        else -> "Couldn't load this (${e.javaClass.simpleName})."
    }
}
