package me.ash.reader.ui.page.adaptive

import android.net.Uri
import androidx.compose.ui.util.fastFirstOrNull
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Date
import java.security.MessageDigest
import javax.inject.Inject
import kotlin.collections.any
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import me.ash.reader.domain.data.ArticlePagingListUseCase
import me.ash.reader.domain.data.DiffMapHolder
import me.ash.reader.domain.data.FilterState
import me.ash.reader.domain.data.FilterStateUseCase
import me.ash.reader.domain.data.GroupWithFeedsListUseCase
import me.ash.reader.domain.data.PagerData
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.general.MarkAsReadConditions
import me.ash.reader.domain.service.GoogleReaderRssService
import me.ash.reader.domain.service.LocalRssService
import me.ash.reader.domain.service.RssService
import me.ash.reader.domain.service.SyncWorker
import me.ash.reader.infrastructure.android.AndroidImageDownloader
import me.ash.reader.infrastructure.android.TextToSpeechManager
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.preference.PullToLoadNextFeedPreference
import me.ash.reader.infrastructure.preference.SettingsProvider
import me.ash.reader.infrastructure.rss.ReaderCacheHelper
import me.ash.reader.infrastructure.translation.TranslationService
import timber.log.Timber

private const val TAG = "FlowViewModel"

@OptIn(FlowPreview::class)
@HiltViewModel()
class ArticleListReaderViewModel
@Inject
constructor(
    private val rssService: RssService,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val applicationScope: CoroutineScope,
    val diffMapHolder: DiffMapHolder,
    private val filterStateUseCase: FilterStateUseCase,
    private val groupWithFeedsListUseCase: GroupWithFeedsListUseCase,
    private val settingsProvider: SettingsProvider,
    private val readerCacheHelper: ReaderCacheHelper,
    val textToSpeechManager: TextToSpeechManager,
    private val imageDownloader: AndroidImageDownloader,
    private val translationService: TranslationService,
    private val articleListUseCase: ArticlePagingListUseCase,
    workManager: WorkManager,
) : ViewModel() {

    val flowUiState: StateFlow<FlowUiState?> =
        articleListUseCase.pagerFlow
            .combine(groupWithFeedsListUseCase.groupWithFeedListFlow) {
                pagerData,
                groupWithFeedsList ->
                val filterState = pagerData.filterState
                var nextFilterState: FilterState? = null
                if (filterState.group != null) {
                    val groupList = groupWithFeedsList.map { it.group }
                    val index = groupList.indexOfFirst { it.id == filterState.group.id }
                    if (index != -1) {
                        val nextGroup = groupList.getOrNull(index + 1)
                        if (nextGroup != null) {
                            nextFilterState = filterState.copy(group = nextGroup)
                        }
                    } else {
                        val allGroupList =
                            rssService.get().queryAllGroupWithFeeds().map { it.group }
                        val index = allGroupList.indexOfFirst { it.id == filterState.group.id }
                        if (index != -1) {
                            val nextGroup =
                                allGroupList.subList(index, allGroupList.size).fastFirstOrNull {
                                    groupList.map { it.id }.contains(it.id)
                                }
                            if (nextGroup != null) {
                                nextFilterState = filterState.copy(group = nextGroup)
                            }
                        }
                    }
                } else if (filterState.feed != null) {
                    val feedList = groupWithFeedsList.flatMap { it.feeds }
                    val index = feedList.indexOfFirst { it.id == filterState.feed.id }
                    if (index != -1) {
                        val nextFeed = feedList.getOrNull(index + 1)
                        if (nextFeed != null) {
                            nextFilterState = filterState.copy(feed = nextFeed)
                        }
                    } else {
                        val allFeedList =
                            rssService.get().queryAllGroupWithFeeds().flatMap { it.feeds }
                        val index = allFeedList.indexOfFirst { it.id == filterState.feed.id }
                        if (index != -1) {
                            val nextFeed =
                                allFeedList.subList(index, allFeedList.size).fastFirstOrNull {
                                    feedList.map { it.id }.contains(it.id)
                                }
                            if (nextFeed != null) {
                                nextFilterState = filterState.copy(feed = nextFeed)
                            }
                        }
                    }
                }
                FlowUiState(nextFilterState = nextFilterState, pagerData = pagerData)
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val syncWorkerStatusFlow =
        workManager
            .getWorkInfosByTagFlow(SyncWorker.SYNC_TAG)
            .map { it.any { workInfo -> workInfo.state == WorkInfo.State.RUNNING } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _isSyncingFlow = MutableStateFlow(false)
    val isSyncingFlow = _isSyncingFlow.asStateFlow()

    init {
        viewModelScope.launch {
            syncWorkerStatusFlow.debounce(500L).collect { _isSyncingFlow.value = it }
        }
        viewModelScope.launch {
            translationService.articleMetadataTranslationEnabled.collect { enabled ->
                if (!enabled) {
                    synchronized(metadataQueueLock) {
                        metadataQueue.clear()
                        // Let the cancelled worker clear its own Job reference in finally.
                        metadataQueueJob?.cancel()
                    }
                    _translatedArticleMetadata.value = emptyMap()
                }
            }
        }
    }

    fun currentArticleMetadataTranslationSignature(): String {
        val providerId = translationService.selectedProviderId()
        return listOf(
            providerId,
            translationService.providerCacheRevision(providerId),
            translationService.sourceLanguage().takeUnless { it == "auto" }.orEmpty(),
            translationService.targetLanguage(),
        ).joinToString("\u0000")
    }

    /**
     * Queue only visible RSS cards. The single worker keeps public APIs such as MyMemory
     * serialized and avoids spending a cloud quota on articles the user has not opened.
     */
    fun translateVisibleArticleMetadata(articles: List<ArticleWithFeed>) {
        if (!translationService.articleMetadataTranslationEnabled.value) return

        val sourceLanguage = translationService.sourceLanguage()
        val selectedProvider = translationService.availableProviders()
            .firstOrNull { it.id == translationService.selectedProviderId() }
        if (
            selectedProvider?.capabilities?.requiresExplicitSourceLanguage == true &&
            sourceLanguage == "auto"
        ) return

        val signature = currentArticleMetadataTranslationSignature()
        val visibleArticleIds = articles.map { it.article.id }.toSet()
        synchronized(metadataQueueLock) {
            // Drop requests that were queued for cards which have already left the viewport.
            val noLongerVisible = metadataQueue.keys.filter { it !in visibleArticleIds }
            noLongerVisible.forEach { articleId -> metadataQueue.remove(articleId) }
            if (noLongerVisible.isNotEmpty()) {
                _translatedArticleMetadata.update { current ->
                    current.filterKeys { it !in noLongerVisible }
                }
            }

            articles.distinctBy { it.article.id }.forEach { articleWithFeed ->
                val articleId = articleWithFeed.article.id
                val sourceContentSignature = articleMetadataSourceSignature(
                    articleWithFeed.article.title,
                    articleWithFeed.article.shortDescription,
                )
                val existing = _translatedArticleMetadata.value[articleId]
                if (
                    (
                        existing?.settingsSignature == signature &&
                        existing.sourceContentSignature == sourceContentSignature &&
                        existing.error == null
                    ) ||
                    articleId in metadataQueue
                ) {
                    return@forEach
                }
                _translatedArticleMetadata.update {
                    it + (articleId to ArticleMetadataTranslation(
                        settingsSignature = signature,
                        sourceContentSignature = sourceContentSignature,
                        isTranslating = true,
                    ))
                }
                metadataQueue[articleId] = articleWithFeed
            }
            ensureMetadataQueueWorkerLocked()
        }
    }

    private fun ensureMetadataQueueWorkerLocked() {
        if (metadataQueueJob?.isActive == true || metadataQueue.isEmpty()) return
        metadataQueueJob = viewModelScope.launch(ioDispatcher) {
            val thisWorker = currentCoroutineContext()[Job]
            try {
                while (true) {
                    val next = synchronized(metadataQueueLock) {
                        val articleId = metadataQueue.keys.firstOrNull()
                        if (articleId == null) null else articleId to metadataQueue.remove(articleId)
                    } ?: break
                    val articleId = next.first
                    val article = next.second ?: continue
                    val sourceContentSignature = articleMetadataSourceSignature(
                        article.article.title,
                        article.article.shortDescription,
                    )
                    val providerId = translationService.selectedProviderId()
                    val targetLanguage = translationService.targetLanguage()
                    val sourceLanguage = translationService.sourceLanguage()
                        .takeUnless { it == "auto" }
                    val signature = listOf(
                        providerId,
                        sourceLanguage.orEmpty(),
                        targetLanguage,
                    ).joinToString("\u0000")

                    try {
                        val translatedTitle = article.article.title
                            .takeIf { it.isNotBlank() }
                            ?.let {
                                translationService.translateText(
                                    text = it,
                                    targetLanguage = targetLanguage,
                                    sourceLanguage = sourceLanguage,
                                    providerId = providerId,
                                ).translatedText
                            }
                        val translatedSummary = article.article.shortDescription
                            .takeIf { it.isNotBlank() }
                            ?.let {
                                translationService.translateText(
                                    text = it,
                                    targetLanguage = targetLanguage,
                                    sourceLanguage = sourceLanguage,
                                    providerId = providerId,
                                ).translatedText
                            }
                        if (
                            translationService.articleMetadataTranslationEnabled.value &&
                            currentArticleMetadataTranslationSignature() == signature
                        ) {
                            _translatedArticleMetadata.update { current ->
                                current + (articleId to ArticleMetadataTranslation(
                                    settingsSignature = signature,
                                    sourceContentSignature = sourceContentSignature,
                                    translatedTitle = translatedTitle,
                                    translatedSummary = translatedSummary,
                                ))
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        Timber.w(error, "Article metadata translation failed: $articleId")
                        if (
                            translationService.articleMetadataTranslationEnabled.value &&
                            currentArticleMetadataTranslationSignature() == signature
                        ) {
                            _translatedArticleMetadata.update { current ->
                                current + (articleId to ArticleMetadataTranslation(
                                    settingsSignature = signature,
                                    sourceContentSignature = sourceContentSignature,
                                    error = error.message ?: "Translation failed",
                                ))
                            }
                        }
                    }
                }
            } finally {
                synchronized(metadataQueueLock) {
                    // A cancelled worker must not clear the reference to a newer worker.
                    if (metadataQueueJob === thisWorker) {
                        metadataQueueJob = null
                        if (
                            metadataQueue.isNotEmpty() &&
                            translationService.articleMetadataTranslationEnabled.value
                        ) {
                            ensureMetadataQueueWorkerLocked()
                        }
                    }
                }
            }
        }
    }

    fun updateReadStatus(
        groupId: String?,
        feedId: String?,
        articleId: String?,
        conditions: MarkAsReadConditions,
        isUnread: Boolean,
    ) {
        applicationScope.launch(ioDispatcher) {
            rssService
                .get()
                .markAsRead(
                    groupId = groupId,
                    feedId = feedId,
                    articleId = articleId,
                    before = conditions.toDate(),
                    isUnread = isUnread,
                )
        }
    }

    fun updateStarredStatus(articleId: String?, isStarred: Boolean) {
        applicationScope.launch(ioDispatcher) {
            if (articleId != null) {
                rssService.get().markAsStarred(articleId = articleId, isStarred = isStarred)
            }
        }
    }

    fun markAsReadFromListByDate(date: Date, isBefore: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            val items =
                articleListUseCase.itemSnapshotList
                    .filterIsInstance<ArticleFlowItem.Article>()
                    .map { it.articleWithFeed }
                    .filter {
                        if (isBefore) {
                            date > it.article.date && it.article.isUnread
                        } else {
                            date < it.article.date && it.article.isUnread
                        }
                    }
                    .distinctBy { it.article.id }

            diffMapHolder.updateDiff(articleWithFeed = items.toTypedArray(), isUnread = false)
        }
    }

    fun loadNextFeedOrGroup() {
        viewModelScope.launch {
            if (
                settingsProvider.settings.pullToSwitchFeed ==
                    PullToLoadNextFeedPreference.MarkAsReadAndLoadNextFeed
            ) {
                markAllAsRead()
            }
            flowUiState.value?.nextFilterState?.let { filterStateUseCase.updateFilterState(it) }
        }
    }

    fun markAllAsRead() {
        viewModelScope.launch {
            val items =
                articleListUseCase.itemSnapshotList.items
                    .filterIsInstance<ArticleFlowItem.Article>()
                    .map { it.articleWithFeed }

            diffMapHolder.updateDiff(articleWithFeed = items.toTypedArray(), isUnread = false)
        }
    }

    fun sync() {
        diffMapHolder.commitDiffsToDb()
        viewModelScope.launch {
            _isSyncingFlow.value = true
            val isSyncing = syncWorkerStatusFlow.value
            if (!isSyncing) {
                delay(1000L)
                if (syncWorkerStatusFlow.value == false) {
                    _isSyncingFlow.value = false
                }
            }
        }
        applicationScope.launch(ioDispatcher) {
            val filterState = filterStateUseCase.filterStateFlow.value
            val service = rssService.get()
            when (service) {
                is LocalRssService ->
                    service.doSyncOneTime(
                        feedId = filterState.feed?.id,
                        groupId = filterState.group?.id,
                    )

                is GoogleReaderRssService ->
                    service.doSyncOneTime(
                        feedId = filterState.feed?.id,
                        groupId = filterState.group?.id,
                    )

                else -> service.doSyncOneTime()
            }
        }
    }

    fun resetFilter() =
        filterStateUseCase.updateFilterState(feed = null, group = null, searchContent = null)

    fun changeFilter(filterState: FilterState) {
        filterStateUseCase.updateFilterState(
            filterState.feed,
            filterState.group,
            filterState.filter,
        )
    }

    fun inputSearchContent(content: String? = null) {
        if (content != filterStateUseCase.filterStateFlow.value.searchContent)
            filterStateUseCase.updateFilterState(searchContent = content)
    }

    private val _readingUiState = MutableStateFlow(ReadingUiState())
    val readingUiState: StateFlow<ReadingUiState> = _readingUiState.asStateFlow()

    private val _readerState: MutableStateFlow<ReaderState> = MutableStateFlow(ReaderState())
    val readerStateStateFlow = _readerState.asStateFlow()

    private val currentArticle: Article?
        get() = readingUiState.value.articleWithFeed?.article

    private var translationJob: Job? = null

    private val _translatedArticleMetadata =
        MutableStateFlow<Map<String, ArticleMetadataTranslation>>(emptyMap())
    val translatedArticleMetadata = _translatedArticleMetadata.asStateFlow()
    val articleMetadataTranslationEnabled = translationService.articleMetadataTranslationEnabled

    private val metadataQueueLock = Any()
    private val metadataQueue = LinkedHashMap<String, ArticleWithFeed>()
    private var metadataQueueJob: Job? = null

    private val currentFeed: Feed?
        get() = readingUiState.value.articleWithFeed?.feed

    fun initData(articleId: String, listIndex: Int? = null) {
        translationJob?.cancel()
        translationJob = null
        viewModelScope.launch {
            val snapshotList = articleListUseCase.itemSnapshotList

            val itemByIndex =
                listIndex?.let { snapshotList.getOrNull(it) as? ArticleFlowItem.Article }

            val itemFromList =
                if (itemByIndex != null && itemByIndex.articleWithFeed.article.id != articleId) {
                    itemByIndex
                } else {
                    snapshotList.find { item ->
                        item is ArticleFlowItem.Article &&
                            item.articleWithFeed.article.id == articleId
                    } as? ArticleFlowItem.Article
                }

            val item =
                itemByIndex?.articleWithFeed
                    ?: (itemFromList?.articleWithFeed
                        ?: rssService.get().findArticleById(articleId)!!)

            if (diffMapHolder.checkIfUnread(item)) {
                diffMapHolder.updateDiff(item, isUnread = false)
            }
            item.run {
                _readingUiState.update {
                    it.copy(articleWithFeed = this, isStarred = article.isStarred, isUnread = false)
                }
                _readerState.update {
                    it.copy(
                            articleId = article.id,
                            feedName = feed.name,
                            title = article.title,
                            author = article.author,
                            link = article.link,
                            publishedDate = article.date,
                            translatedTitle = null,
                            translatedContent = null,
                            isTranslated = false,
                            isBilingual = false,
                            isTranslating = false,
                            translationProgress = null,
                            translationError = null,
                        )
                        .prefetchArticleId()
                        .renderContent(this)
                }
                maybeAutoTranslate(article.id)
            }
        }
    }

    fun clearReadingData() {
        translationJob?.cancel()
        translationJob = null
        _readingUiState.update { ReadingUiState() }
        _readerState.update { ReaderState() }
    }

    suspend fun ReaderState.renderContent(articleWithFeed: ArticleWithFeed): ReaderState {
        val contentState =
            if (articleWithFeed.feed.isFullContent) {
                val fullContent =
                    readerCacheHelper.readFullContent(articleWithFeed.article.id).getOrNull()
                if (fullContent != null) ReaderState.FullContent(fullContent)
                else {
                    renderFullContent()
                    ReaderState.Loading
                }
            } else ReaderState.Description(articleWithFeed.article.rawDescription)

        return copy(content = contentState)
    }

    fun renderDescriptionContent() {
        translationJob?.cancel()
        translationJob = null
        _readerState.update {
            it.copy(
                content = ReaderState.Description(content = currentArticle?.rawDescription ?: ""),
            translatedTitle = null,
            translatedContent = null,
            isTranslated = false,
            isTranslating = false,
            translationProgress = null,
            translationError = null,
            )
        }
        currentArticle?.id?.let(::maybeAutoTranslate)
    }

    fun renderFullContent() {
        translationJob?.cancel()
        translationJob = null
        val articleId = currentArticle?.id ?: return
        val article = currentArticle ?: return
        val fetchJob =
            viewModelScope.launch {
                readerCacheHelper
                    .readOrFetchFullContent(article)
                    .onSuccess { content ->
                        _readerState.update {
                            it.copy(
                                content = ReaderState.FullContent(content = content),
                                translatedTitle = null,
                                translatedContent = null,
                                isTranslated = false,
                                isTranslating = false,
                                translationProgress = null,
                                translationError = null,
                            )
                        }
                        maybeAutoTranslate(articleId)
                    }
                    .onFailure { th ->
                        _readerState.update {
                            it.copy(
                                content = ReaderState.Error(th.message.toString()),
                                translatedTitle = null,
                                translatedContent = null,
                                isTranslated = false,
                                isTranslating = false,
                                translationProgress = null,
                                translationError = null,
                            )
                        }
                    }
            }
        viewModelScope.launch {
            delay(100L)
            if (fetchJob.isActive) {
                setLoading()
            }
        }
    }

    private fun maybeAutoTranslate(articleId: String) {
        val feedId = currentFeed?.id ?: return
        if (_readerState.value.articleId != articleId) return
        if (_readerState.value.content is ReaderState.Loading || _readerState.value.content is ReaderState.Error) return
        if (translationService.isAutoTranslationEnabled(feedId)) toggleTranslation()
    }

    fun updateReadStatus(isUnread: Boolean) {
        readingUiState.value.articleWithFeed?.let {
            diffMapHolder.updateDiff(it, isUnread = isUnread)
        }
        _readingUiState.update {
            it.copy(isUnread = diffMapHolder.checkIfUnread(it.articleWithFeed!!))
        }
    }

    fun updateStarredStatus(isStarred: Boolean) {
        applicationScope.launch(ioDispatcher) {
            _readingUiState.update { it.copy(isStarred = isStarred) }
            currentArticle?.let {
                rssService.get().markAsStarred(articleId = it.id, isStarred = isStarred)
            }
        }
    }


    /**
     * Translate the currently displayed article. The original RSS/HTML is never overwritten;
     * switching the state back to false immediately restores the source content.
     */
    fun cancelTranslation() {
        if (!_readerState.value.isTranslating) return
        translationJob?.cancel()
        translationJob = null
        _readerState.update {
            it.copy(isTranslating = false, translationProgress = null)
        }
    }

    fun toggleTranslation() {
        val state = _readerState.value
        if (state.articleId == null || state.isTranslating) return

        if (state.isTranslated) {
            _readerState.update { it.copy(isTranslated = false) }
            return
        }

        if (state.translatedContent != null) {
            _readerState.update {
                it.copy(
                    isTranslated = true,
                    isBilingual = translationService.isBilingualReadingEnabled(),
                )
            }
            return
        }

        val sourceContent = when (val content = state.content) {
            is ReaderState.Description -> content.content
            is ReaderState.FullContent -> content.content
            is ReaderState.Error -> currentArticle?.rawDescription ?: return
            ReaderState.Loading -> return
        }
        if (sourceContent.isBlank()) return

        val articleId = state.articleId
        val sourceTitle = state.title.orEmpty()
        val targetLanguage = translationService.targetLanguage()
        val sourceLanguage = translationService.sourceLanguage().takeUnless { it == "auto" }

        _readerState.update {
            it.copy(isTranslating = true, translationProgress = 0f, translationError = null)
        }

        translationJob = viewModelScope.launch {
            try {
                val providerId = translationService.selectedProviderId()
                val translatedContent = translationService.translateHtml(
                    html = sourceContent,
                    targetLanguage = targetLanguage,
                    providerId = providerId,
                    sourceLanguage = sourceLanguage,
                    onProgress = { progress ->
                        _readerState.update { current ->
                            if (current.articleId == articleId && current.isTranslating) {
                                current.copy(translationProgress = progress.coerceIn(0f, 1f) * 0.9f)
                            } else current
                        }
                    },
                )
                _readerState.update { current ->
                    if (current.articleId == articleId && current.isTranslating) {
                        current.copy(translationProgress = 0.95f)
                    } else current
                }
                val translatedTitle = translationService.translateText(
                    text = sourceTitle,
                    targetLanguage = targetLanguage,
                    providerId = providerId,
                    sourceLanguage = sourceLanguage,
                ).translatedText

                _readerState.update { current ->
                    if (current.articleId != articleId) current
                    else current.copy(
                        translatedTitle = translatedTitle,
                        translatedContent = translatedContent,
                        isTranslated = true,
                        isBilingual = translationService.isBilingualReadingEnabled(),
                        isTranslating = false,
                        translationProgress = null,
                        translationError = null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Timber.e(error, "Article translation failed")
                _readerState.update { current ->
                    if (current.articleId != articleId) current
                    else current.copy(
                        isTranslating = false,
                        translationProgress = null,
                        translationError = error.message ?: "Translation failed",
                    )
                }
            }
        }
    }

    private fun setLoading() {
        _readerState.update {
            it.copy(
                content = ReaderState.Loading,
                translatedTitle = null,
                translatedContent = null,
                isTranslated = false,
                isTranslating = false,
                translationProgress = null,
                translationError = null,
            )
        }
    }

    fun ReaderState.prefetchArticleId(): ReaderState {
        val items = articleListUseCase.itemSnapshotList
        val currentId = currentArticle?.id
        val index =
            items.indexOfFirst { item ->
                item is ArticleFlowItem.Article && item.articleWithFeed.article.id == currentId
            }
        var previousArticle: ReaderState.PrefetchResult? = null
        var nextArticle: ReaderState.PrefetchResult? = null

        if (index != -1 || currentId == null) {
            val prevIterator = items.listIterator(index)
            while (prevIterator.hasPrevious()) {
                val previousIndex = prevIterator.previousIndex()
                val prev = prevIterator.previous()
                if (prev is ArticleFlowItem.Article) {
                    previousArticle =
                        ReaderState.PrefetchResult(
                            articleId = prev.articleWithFeed.article.id,
                            index = previousIndex,
                        )
                    break
                }
            }
            val nextIterator = items.listIterator(index + 1)
            while (nextIterator.hasNext()) {
                val nextIndex = nextIterator.nextIndex()
                val next = nextIterator.next()
                if (
                    next is ArticleFlowItem.Article && next.articleWithFeed.article.id != currentId
                ) {
                    nextArticle =
                        ReaderState.PrefetchResult(
                            articleId = next.articleWithFeed.article.id,
                            index = nextIndex,
                        )
                    break
                }
            }
        }

        Timber.d("$previousArticle, $nextArticle, $listIndex")
        return copy(nextArticle = nextArticle, previousArticle = previousArticle, listIndex = index)
    }

    fun downloadImage(
        url: String,
        onSuccess: (Uri) -> Unit = {},
        onFailure: (Throwable) -> Unit = {},
    ) {
        viewModelScope.launch {
            imageDownloader.downloadImage(url).onSuccess(onSuccess).onFailure(onFailure)
        }
    }
}

data class FlowUiState(val pagerData: PagerData, val nextFilterState: FilterState? = null)

data class ArticleMetadataTranslation(
    val settingsSignature: String,
    val sourceContentSignature: String,
    val translatedTitle: String? = null,
    val translatedSummary: String? = null,
    val isTranslating: Boolean = false,
    val error: String? = null,
)

internal fun articleMetadataSourceSignature(title: String, summary: String): String {
    val material = "${title.length}:$title${summary.length}:$summary"
    return MessageDigest.getInstance("SHA-256")
        .digest(material.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

data class ReadingUiState(
    val articleWithFeed: ArticleWithFeed? = null,
    val isUnread: Boolean = false,
    val isStarred: Boolean = false,
)

data class ReaderState(
    val articleId: String? = null,
    val feedName: String = "",
    val title: String? = null,
    val author: String? = null,
    val link: String? = null,
    val publishedDate: Date = Date(0L),
    val translatedTitle: String? = null,
    val translatedContent: String? = null,
    val isTranslated: Boolean = false,
    val isBilingual: Boolean = false,
    val isTranslating: Boolean = false,
    val translationProgress: Float? = null,
    val translationError: String? = null,
    val content: ContentState = Loading,
    val listIndex: Int? = null,
    val nextArticle: PrefetchResult? = null,
    val previousArticle: PrefetchResult? = null,
) {
    data class PrefetchResult(val articleId: String, val index: Int)

    sealed interface ContentState {
        val text: String?
            get() {
                return when (this) {
                    is Description -> content
                    is Error -> message
                    is FullContent -> content
                    Loading -> null
                }
            }
    }

    data class FullContent(val content: String) : ContentState

    data class Description(val content: String) : ContentState

    data class Error(val message: String) : ContentState

    data object Loading : ContentState
}
