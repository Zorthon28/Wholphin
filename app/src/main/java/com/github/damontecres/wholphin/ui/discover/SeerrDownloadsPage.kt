package com.github.damontecres.wholphin.ui.discover

import androidx.compose.animation.core.animateFloatAsState
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.api.seerr.infrastructure.ClientException
import com.github.damontecres.wholphin.api.seerr.model.MediaRequest
import com.github.damontecres.wholphin.api.seerr.model.NullSeerrQueueItem
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.SeerrServerRepository
import com.github.damontecres.wholphin.services.SeerrService
import com.github.damontecres.wholphin.services.SeerrConnectionStatus
import com.github.damontecres.wholphin.ui.AppColors
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.Cards
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.cards.ItemCardImage
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.detail.CardGrid
import com.github.damontecres.wholphin.ui.detail.CardGridItem
import com.github.damontecres.wholphin.ui.enableMarquee
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import javax.inject.Inject

data class DownloadGridItem(
    val request: MediaRequest,
    val item: DiscoverItem,
    val progressPercent: Double?,
    val statusText: String,
    val timeLeft: String?,
    @StringRes val blocker: Int? = null,
) : CardGridItem {
    override val gridId: String = request.id.toString()
    override val playable: Boolean = false
    override val sortName: String = request.updatedAt ?: "0000"
}

data class SeerrDownloadsState(
    val downloads: DataLoadingState<List<DownloadGridItem>>,
) {
    companion object {
        val EMPTY = SeerrDownloadsState(DataLoadingState.Pending)
    }
}

@HiltViewModel
class SeerrDownloadsViewModel
    @Inject
    constructor(
        private val seerrServerRepository: SeerrServerRepository,
        private val seerrService: SeerrService,
        val navigationManager: NavigationManager,
        private val backdropService: BackdropService,
    ) : ViewModel() {
        val state = MutableStateFlow(SeerrDownloadsState.EMPTY)

        init {
            viewModelScope.launchIO {
                backdropService.clearBackdrop()
            }
            viewModelScope.launchIO {
                while (currentCoroutineContext().isActive) {
                    if (seerrServerRepository.connection.value.connection is SeerrConnectionStatus.Success) {
                        fetchDownloads()
                    } else {
                        state.update { it.copy(downloads = DataLoadingState.Pending) }
                    }
                    delay(5000L) // Auto-refresh progress every 5 seconds
                }
            }
        }

        private suspend fun fetchDownloads() {
            try {
                val api = seerrService.api
                val mediaRequests = mutableListOf<MediaRequest>()
                do {
                    val response = api.requestApi.requestGet(take = 50, skip = mediaRequests.size)
                    val page = response.results.orEmpty()
                    mediaRequests.addAll(page)
                    val hasMore = mediaRequests.size < (response.pageInfo?.results ?: mediaRequests.size)
                } while (page.isNotEmpty() && hasMore)

                val queue =
                    try {
                        api.mediaApi.mediaQueueGet().items
                    } catch (ex: ClientException) {
                        if (ex.statusCode !in setOf(404, 405)) throw ex
                        emptyList()
                    }
                val byMedia = queue.groupBy { Triple(it.mediaType, it.tmdbId, it.is4k == true) }

                val semaphore = Semaphore(4)
                val downloads =
                    mediaRequests.distinctBy { it.id }.mapNotNull { request ->
                        if (request.media?.tmdbId != null) {
                            val mediaStatus = request.media.status
                            val isDownloadingOrPending =
                                mediaStatus == SeerrAvailability.PROCESSING.status ||
                                    mediaStatus == SeerrAvailability.PENDING.status ||
                                    request.status == 1 || request.status == 2

                            val queueItems = byMedia[Triple(request.type, request.media.tmdbId, request.is4k == true)].orEmpty()
                            if (isDownloadingOrPending || queueItems.isNotEmpty()) {
                                viewModelScope.async(WholphinDispatchers.IO) {
                                    semaphore.withPermit {
                                        val type = SeerrItemType.fromString(request.type)
                                        val item =
                                            when (type) {
                                                SeerrItemType.MOVIE -> {
                                                    api.moviesApi
                                                        .movieMovieIdGet(movieId = request.media.tmdbId)
                                                        .let { seerrService.createDiscoverItem(it) }
                                                }

                                                SeerrItemType.TV -> {
                                                    api.tvApi
                                                        .tvTvIdGet(tvId = request.media.tmdbId)
                                                        .let { seerrService.createDiscoverItem(it) }
                                                }

                                                else -> null
                                            }

                                        if (item != null) {
                                            val queueItem = selectQueueItem(queueItems)
                                            val pending = mediaStatus == SeerrAvailability.PENDING.status || request.status == 1
                                            val downloads = if (request.is4k == true) request.media.downloadStatus4k else request.media.downloadStatus
                                            val trackedDownload = downloads.orEmpty().firstOrNull()
                                            val progress = queueItem?.progress?.takeUnless { queueItem.status == "searching" } ?: trackedDownload?.let {
                                                val size = it.propertySize?.toDouble() ?: 0.0
                                                val left = it.sizeLeft?.toDouble()
                                                if (size > 0 && left != null) ((size - left) / size * 100).coerceIn(0.0, 100.0) else null
                                            }
                                            val status = queueItem?.status ?: trackedDownload?.status ?: if (pending) "pending" else "processing"
                                            val timeLeft = (queueItem?.timeLeft ?: trackedDownload?.timeLeft)
                                                ?.takeIf { it.isNotBlank() && it != "~0m" && it != "0:00:00" }

                                            DownloadGridItem(
                                                request = request,
                                                item = item,
                                                progressPercent = progress,
                                                statusText = status.replaceFirstChar { it.uppercase() },
                                                timeLeft = timeLeft,
                                                blocker = downloadBlocker(queueItem, status, pending),
                                            )
                                        } else null
                                    }
                                }
                            } else null
                        } else null
                    }

                val results = downloads.awaitAll().filterNotNull()
                state.update { it.copy(downloads = DataLoadingState.Success(results)) }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.e(ex, "Error fetching downloads from Seerr")
                state.update { it.copy(downloads = DataLoadingState.Error(ex)) }
            }
        }
    }

private fun selectQueueItem(items: List<NullSeerrQueueItem>): NullSeerrQueueItem? =
    items.firstOrNull { it.blocker != null || it.swarmHealth == "stalled" || it.status in setOf("stalled", "failed", "warning") }
        ?: items.firstOrNull { it.status in setOf("downloading", "importing", "processing") }
        ?: items.firstOrNull()

@StringRes
private fun downloadBlocker(item: NullSeerrQueueItem?, status: String, pending: Boolean): Int? =
    when {
        pending -> R.string.tv_download_pending
        item?.blocker == "provider" -> R.string.tv_download_provider
        item?.blocker == "import" -> R.string.tv_download_import
        item?.blocker == "storage" -> R.string.tv_download_storage
        item?.blocker == "no_seeds" || item?.swarmHealth == "stalled" -> R.string.tv_download_no_seeds
        item?.blocker == "paused" || status.equals("paused", ignoreCase = true) -> R.string.tv_download_paused
        item?.blocker == "failed" || status.equals("failed", ignoreCase = true) -> R.string.tv_download_failed
        status.equals("stalled", ignoreCase = true) -> R.string.tv_download_stalled
        else -> null
    }

@Composable
fun SeerrDownloadsPage(
    focusRequesterOnEmpty: FocusRequester?,
    positionCallback: (columns: Int, position: Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SeerrDownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState(SeerrDownloadsState.EMPTY)
    val columns = 5

    when (val downloadsState = state.downloads) {
        is DataLoadingState.Error -> {
            ErrorMessage(downloadsState.message, downloadsState.exception, modifier.focusable())
        }

        DataLoadingState.Loading,
        DataLoadingState.Pending,
        -> {
            LoadingPage(modifier.focusable())
        }

        is DataLoadingState.Success<List<DownloadGridItem>> -> {
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(downloadsState.data) {
                if (downloadsState.data.isNotEmpty()) {
                    focusRequester.tryRequestFocus()
                } else {
                    focusRequesterOnEmpty?.tryRequestFocus()
                }
            }

            Column(modifier = modifier) {
                if (downloadsState.data.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "📥",
                                style = MaterialTheme.typography.displayLarge,
                            )
                            Text(
                                text = stringResource(R.string.no_active_downloads),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                } else {
                    CardGrid(
                        pager = downloadsState.data,
                        onClickItem = { _, item ->
                            viewModel.navigationManager.navigateTo(Destination.DiscoveredItem(item.item))
                        },
                        onLongClickItem = { _, _ -> },
                        onClickPlay = { _, _ -> },
                        letterPosition = { 0 },
                        gridFocusRequester = focusRequester,
                        showJumpButtons = false,
                        showLetterButtons = false,
                        spacing = 20.dp,
                        cardContent = @Composable { (item, index, onClick, onLongClick, widthPx, mod) ->
                            DownloadTrackerCard(
                                downloadItem = item,
                                onClick = onClick,
                                onLongClick = onLongClick,
                                modifier = mod,
                            )
                        },
                        columns = columns,
                        positionCallback = positionCallback,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
fun DownloadTrackerCard(
    downloadItem: DownloadGridItem?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = Cards.height2x3 * AspectRatios.TALL,
) {
    val item = downloadItem?.item
    val progress = downloadItem?.progressPercent

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.size(width, Dp.Unspecified),
    ) {
        Card(
            modifier =
                Modifier
                    .size(width, Dp.Unspecified)
                    .aspectRatio(AspectRatios.TALL),
            onClick = onClick,
            onLongClick = onLongClick,
            colors =
                CardDefaults.colors(
                    containerColor = Color.Transparent,
                ),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                ItemCardImage(
                    imageUrl = item?.posterUrl,
                    name = item?.title,
                    showOverlay = false,
                    favorite = false,
                    watched = false,
                    unwatchedCount = 0,
                    watchedPercent = progress,
                    numberOfVersions = -1,
                    useFallbackText = false,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )

                // Top Badge: Status
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.75f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = downloadItem?.statusText ?: "Active",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.Discover.Blue,
                    )
                }

                // Bottom Overlay: Progress Bar + Time Remaining
                Column(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.80f))
                            .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    // Visual Linear Progress Bar
                    if (progress != null) Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.Gray.copy(alpha = 0.5f)),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth((progress / 100.0).toFloat().coerceIn(0f, 1f))
                                    .height(4.dp)
                                    .background(MaterialTheme.colorScheme.primary),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        if (progress != null) {
                            Text(
                                text = "${progress.toInt()}%",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                            )
                        }
                        if (!downloadItem?.timeLeft.isNullOrBlank()) {
                            Text(
                                text = downloadItem?.timeLeft ?: "",
                                fontSize = 10.sp,
                                color = Color.LightGray,
                            )
                        }
                    }
                }
            }
        }

        // Title below card
        Text(
            text = item?.title.orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().basicMarquee(),
        )
        downloadItem?.blocker?.let { reason ->
            Text(
                text = stringResource(reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
