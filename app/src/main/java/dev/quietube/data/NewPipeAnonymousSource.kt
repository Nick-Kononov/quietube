package dev.quietube.data

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchExtractor.NothingFoundException
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

private class CommentCursor(val url: String,val page: Page) : Continuation
private class PlaylistCursor(val id: String, val page: Page) : Continuation

private data class SearchContinuation(val query: String, val page: Page) : Continuation

@Singleton
class NewPipeAnonymousSource @Inject constructor(private val downloader: ExtractorDownloader) : AnonymousSource {
    // All use, including lazy initialization of extractor global state, happens on IO.
    private val service by lazy {
        NewPipe.init(downloader, Localization("en", "US"), ContentCountry("US"))
        ServiceList.YouTube
    }

    override suspend fun comments(url: String,continuation: Continuation?): Outcome<CommentPage> = withContext(Dispatchers.IO) {
        try {
            val cursor = continuation as? CommentCursor
            if (continuation != null && (cursor == null || cursor.url != url))
                return@withContext Outcome.Failure(VideoError.ExtractionBroken)
            val info = if (cursor == null) org.schabi.newpipe.extractor.comments.CommentsInfo.getInfo(service,url) else null
            if (cursor == null && info == null) return@withContext Outcome.Failure(VideoError.ContentUnavailable)
            if (info?.isCommentsDisabled == true) return@withContext Outcome.Success(CommentPage(emptyList(),disabled = true))
            val page = if (cursor == null) org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage(info!!.relatedItems,info.nextPage,info.errors)
                else org.schabi.newpipe.extractor.comments.CommentsInfo.getMoreItems(service,url,cursor.page)
            var skipped = page.errors.isNotEmpty()
            val comments = page.items.mapNotNull { item ->
                try { CommentMapper.map(item) } catch (_: Exception) { skipped = true; null }
            }.distinctBy { it.id }
            if (comments.isEmpty() && skipped) return@withContext Outcome.Failure(VideoError.ExtractionBroken)
            Outcome.Success(CommentPage(comments,page.nextPage?.takeIf { Page.isValid(it) }?.let { CommentCursor(url,it) },
                total = info?.commentsCount?.takeIf { it >= 0 },skippedItems = skipped))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Outcome.Failure(mapExtractorError(e)) }
    }

    override suspend fun playlist(id: String, continuation: Continuation?): Outcome<VideoPage> = withContext(Dispatchers.IO) {
        try {
            if (!Regex("[A-Za-z0-9_-]{2,150}").matches(id)) return@withContext Outcome.Failure(VideoError.ContentUnavailable)
            val url = "https://www.youtube.com/playlist?list=$id"
            val cursor = continuation as? PlaylistCursor
            if (continuation != null && (cursor == null || cursor.id != id)) return@withContext Outcome.Failure(VideoError.ExtractionBroken)
            val page = if (cursor == null) {
                val info = org.schabi.newpipe.extractor.playlist.PlaylistInfo.getInfo(service, url)
                org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage(info.relatedItems, info.nextPage, info.errors)
            } else org.schabi.newpipe.extractor.playlist.PlaylistInfo.getMoreItems(service, url, cursor.page)
            val videos = page.items.mapNotNull { item ->
                try { Video(item.url, item.name, item.uploaderName.orEmpty(), item.thumbnails.maxByOrNull { it.height }?.url,
                    item.duration, item.viewCount, item.textualUploadDate, item.streamType == StreamType.LIVE_STREAM) }
                catch (_: Exception) { null }
            }
            if (videos.isEmpty() && page.errors.isNotEmpty()) Outcome.Failure(VideoError.ExtractionBroken)
            else Outcome.Success(VideoPage(videos, page.nextPage?.takeIf { Page.isValid(it) }?.let { PlaylistCursor(id, it) },
                skippedItems = page.errors.isNotEmpty()))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Outcome.Failure(mapExtractorError(e)) }
    }

    override suspend fun playback(url: String): Outcome<PlaybackDetails> = withContext(Dispatchers.IO) {
        try {
            val info = org.schabi.newpipe.extractor.stream.StreamInfo.getInfo(service, url)
            val live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
            val video = Video(info.url, info.name, info.uploaderName.orEmpty(),
                info.thumbnails.maxByOrNull { it.height }?.url, info.duration,
                info.viewCount, info.textualUploadDate, live, likeCount = info.likeCount.takeIf { it >= 0 })
            fun delivery(stream: org.schabi.newpipe.extractor.stream.Stream): Delivery = when (stream.deliveryMethod) {
                org.schabi.newpipe.extractor.stream.DeliveryMethod.DASH -> Delivery.Dash
                org.schabi.newpipe.extractor.stream.DeliveryMethod.HLS -> Delivery.Hls
                else -> Delivery.Progressive
            }
            val videos = (info.videoOnlyStreams + info.videoStreams).mapNotNull { stream ->
                try {
                    if (!stream.isUrl || stream.content.isBlank()) null
                    else StreamVariant(stream.content, stream.format?.mimeType,
                        stream.height.takeIf { it > 0 } ?: Regex("[0-9]+").find(stream.getResolution())?.value?.toIntOrNull() ?: 0,
                        stream.bitrate, stream.isVideoOnly(), stream.codec.orEmpty(), delivery(stream))
                } catch (_: Exception) { null }
            }
            val audio = info.audioStreams.mapNotNull { stream ->
                try {
                    if (!stream.isUrl || stream.content.isBlank()) null
                    else StreamVariant(stream.content, stream.format?.mimeType, bitrate = stream.averageBitrate,
                        delivery = delivery(stream), audioRole = when (stream.audioTrackType) {
                            org.schabi.newpipe.extractor.stream.AudioTrackType.ORIGINAL -> AudioRole.Original
                            org.schabi.newpipe.extractor.stream.AudioTrackType.DUBBED -> AudioRole.Dubbed
                            org.schabi.newpipe.extractor.stream.AudioTrackType.DESCRIPTIVE -> AudioRole.Descriptive
                            org.schabi.newpipe.extractor.stream.AudioTrackType.SECONDARY -> AudioRole.Secondary
                            else -> AudioRole.Unknown
                        }, audioLanguage = stream.audioLocale?.toLanguageTag())
                } catch (_: Exception) { null }
            }
            val related = info.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull { item ->
                try {
                    Video(item.url, item.name, item.uploaderName.orEmpty(), item.thumbnails.maxByOrNull { it.height }?.url,
                        item.duration, item.viewCount, item.textualUploadDate, item.streamType == StreamType.LIVE_STREAM)
                } catch (_: Exception) { null }
            }.distinctBy { it.url }
            val details = PlaybackDetails(video, videos, audio, info.hlsUrl?.takeIf { it.isNotBlank() },
                info.dashMpdUrl?.takeIf { it.isNotBlank() }, related)
            if (StreamSelector.select(details, 1080) == null) Outcome.Failure(VideoError.ContentUnavailable)
            else Outcome.Success(details)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Outcome.Failure(mapExtractorError(e)) }
    }

    override suspend fun search(query: String, continuation: Continuation?): Outcome<VideoPage> = withContext(Dispatchers.IO) {
        try {
            if (query.isBlank()) return@withContext Outcome.Success(VideoPage(emptyList()))
            val handler = service.searchQHFactory.fromQuery(query, listOf("videos"), "")
            val items: List<org.schabi.newpipe.extractor.InfoItem>
            val next: Page?
            val suggestion: String?
            val partial: Boolean
            if (continuation != null) {
                val cursor = continuation as? SearchContinuation
                    ?: return@withContext Outcome.Failure(VideoError.ExtractionBroken)
                if (cursor.query != query) return@withContext Outcome.Failure(VideoError.ExtractionBroken)
                val page = SearchInfo.getMoreItems(service, handler, cursor.page)
                items = page.items
                next = page.nextPage
                suggestion = null
                partial = searchPageError(page.errors) != null
                if (items.isEmpty()) searchPageError(page.errors)?.let { return@withContext Outcome.Failure(it) }
            } else {
                val info = SearchInfo.getInfo(service, handler)
                items = info.relatedItems
                next = info.nextPage
                suggestion = info.searchSuggestion?.takeIf { it.isNotBlank() }
                partial = searchPageError(info.errors) != null
                if (items.isEmpty()) searchPageError(info.errors)?.let { return@withContext Outcome.Failure(it) }
            }
            var skipped = partial
            val videos = items.filterIsInstance<StreamInfoItem>().mapNotNull { item ->
                try {
                    if (item.url.isBlank() || item.name.isBlank()) { skipped = true; null }
                    else Video(item.url, item.name, item.uploaderName.orEmpty(),
                        item.thumbnails.maxByOrNull { it.height }?.url,
                        item.duration, item.viewCount, item.textualUploadDate,
                        item.streamType == StreamType.LIVE_STREAM || item.streamType == StreamType.AUDIO_LIVE_STREAM)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    skipped = true
                    null
                }
            }
            if (videos.isEmpty() && skipped) return@withContext Outcome.Failure(VideoError.ExtractionBroken)
            Outcome.Success(VideoPage(videos,
                next?.takeIf { Page.isValid(it) }?.let { SearchContinuation(query, it) }, suggestion, skipped))
        } catch (e: NothingFoundException) {
            Outcome.Success(VideoPage(emptyList()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failure(mapExtractorError(e))
        }
    }
}

internal fun mapExtractorError(error: Exception): VideoError = when (error) {
    is AgeRestrictedContentException -> VideoError.AgeRestricted
    is ContentNotAvailableException -> VideoError.ContentUnavailable
    is ReCaptchaException -> VideoError.RateLimited
    is HttpFailure -> if (error.status == 429) VideoError.RateLimited else VideoError.Network
    is IOException -> VideoError.Network
    else -> VideoError.ExtractionBroken
}

/** NewPipe wraps legitimate empty-search results in an error; distinguish that from parse failure. */
internal fun searchPageError(errors: List<Throwable>): VideoError? = errors
    .firstOrNull { it !is NothingFoundException }
    ?.let { if (it is Exception) mapExtractorError(it) else VideoError.ExtractionBroken }
