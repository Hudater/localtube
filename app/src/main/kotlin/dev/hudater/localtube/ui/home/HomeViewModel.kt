package dev.hudater.localtube.ui.home

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.hudater.localtube.core.data.repository.LibraryRepository
import dev.hudater.localtube.core.data.repository.SourceRepository
import dev.hudater.localtube.core.filesystem.SourceFileSystem
import dev.hudater.localtube.core.model.Channel
import dev.hudater.localtube.core.model.SmbConnectionInfo
import dev.hudater.localtube.core.model.Source
import dev.hudater.localtube.core.model.SourceId
import dev.hudater.localtube.core.model.SourceType
import dev.hudater.localtube.core.model.Video
import dev.hudater.localtube.ui.SelectionState
import dev.hudater.localtube.work.ScanScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray

data class HomeUiState(
    val visibleSources: List<Source> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val videos: List<Video> = emptyList(),
    val isRefreshing: Boolean = false,
    /** Live search text; empty means the feed is unfiltered. */
    val query: String = "",
)

class HomeViewModel(
    private val appContext: Context,
    private val sourceRepository: SourceRepository,
    private val libraryRepository: LibraryRepository,
    private val selectionState: SelectionState,
    private val fileSystemsByType: Map<SourceType, SourceFileSystem>,
) : ViewModel() {

    private val isRefreshing = MutableStateFlow(false)

    /** Live search text for the home feed; empty string = unfiltered. */
    val query = MutableStateFlow("")

    /** Every configured source (hidden included) for the gear-menu export. */
    val sources: StateFlow<List<Source>> =
        sourceRepository.observeAllSources()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uiState: StateFlow<HomeUiState> =
        combine(
            sourceRepository.observeVisibleSources(),
            selectionState.selectedSourceIds,
            selectionState.selectedChannelId,
            isRefreshing,
        ) { visibleSources, _, selectedChannelId, refreshing ->
            Triple(visibleSources, selectedChannelId, refreshing)
        }.flatMapLatest { (visibleSources, selectedChannelId, refreshing) ->
            val effectiveSourceIds = selectionState.effectiveSourceIds(visibleSources)
            combine(
                libraryRepository.observeChannels(effectiveSourceIds),
                libraryRepository.observeFeed(effectiveSourceIds, selectedChannelId),
                query,
            ) { channels, videos, activeQuery ->
                val filtered = filterFeed(videos, channels, activeQuery)
                // Diagnostic: a DB with rows + an empty feed shows up here
                // as videos=0, narrowing "scan didn't write" vs "UI can't
                // read" in one logcat line.
                Log.d(
                    "LocalTubeFeed",
                    "sourceIds=${effectiveSourceIds.map { it.value }} channelId=${selectedChannelId?.value} " +
                        "channels=${channels.size} videos=${filtered.size} query='$activeQuery'",
                )
                HomeUiState(
                    visibleSources = visibleSources,
                    channels = channels,
                    videos = filtered,
                    isRefreshing = refreshing,
                    query = activeQuery,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun onRefresh() {
        isRefreshing.value = true
        ScanScheduler.scanNow(appContext)
        // WorkManager scans asynchronously; we don't currently observe
        // completion, so the refresh spinner is time-boxed rather than
        // tied to work status — see docs/ARCHITECTURE.md open items for
        // wiring WorkInfo observation if this feels too abrupt in practice.
        viewModelScope.launch {
            kotlinx.coroutines.delay(1_500)
            isRefreshing.value = false
        }
    }

    /** Updates the live search text; the feed re-filters on every change. */
    fun onQueryChange(value: String) {
        query.value = value
    }

    /** Resets the search (clear button / collapsing the search field). */
    fun clearQuery() {
        query.value = ""
    }

    /**
     * Live search: keep a video when its title OR its channel's name
     * contains the query (case-insensitive). An empty query means no
     * filtering — channel-name matching only widens the net, a video
     * whose channel name matches stays visible even if its title
     * doesn't contain the text.
     */
    private fun filterFeed(videos: List<Video>, channels: List<Channel>, activeQuery: String): List<Video> {
        val needle = activeQuery.trim()
        if (needle.isEmpty()) return videos
        val matchingChannelIds = channels
            .filter { it.name.contains(needle, ignoreCase = true) }
            .map { it.id }
            .toSet()
        return videos.filter { video ->
            video.title.contains(needle, ignoreCase = true) || video.channelId in matchingChannelIds
        }
    }

    /**
     * Imports sources from a JSON array in the gear-menu export format
     * (label/type/path plus optional SMB details). Entries whose
     * type+path pair is already configured are skipped; the SMB password
     * is never part of the export, so imported SMB sources start without
     * stored credentials — the user re-enters the password once. Malformed
     * JSON toasts "Import failed" instead of crashing.
     */
    fun importSources(json: String) {
        val toImport = try {
            parseImportedSources(json, sources.value)
        } catch (e: Exception) {
            Log.e("LocalTubeImport", "Parsing imported sources failed", e)
            Toast.makeText(appContext, "Import failed", Toast.LENGTH_SHORT).show()
            return
        }
        if (toImport.isEmpty()) {
            Toast.makeText(appContext, "Nothing to import", Toast.LENGTH_SHORT).show()
            return
        }
        viewModelScope.launch {
            var imported = 0
            toImport.forEach { source ->
                try {
                    sourceRepository.addSource(source)
                    imported++
                } catch (e: Exception) {
                    Log.e("LocalTubeImport", "Adding source '${source.label}' failed", e)
                }
            }
            // Same as the Settings screen's add flows: kick a scan so the
            // imported sources' videos actually show up in the feed.
            ScanScheduler.scanNow(appContext)
            Toast.makeText(appContext, "Imported $imported source(s)", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Parses the format produced by HomeScreen's exportSources; throws on
     * malformed JSON so [importSources] can reject the whole file.
     * Duplicates (already configured, or repeated inside the file) are
     * dropped here rather than mid-insert.
     */
    private fun parseImportedSources(json: String, existing: List<Source>): List<Source> {
        val array = JSONArray(json)
        val seenKeys = existing.map { it.type to it.path }.toMutableSet()
        val parsed = mutableListOf<Source>()
        for (i in 0 until array.length()) {
            val entry = array.getJSONObject(i)
            val type = SourceType.valueOf(entry.getString("type"))
            val path = entry.getString("path")
            val key = type to path
            if (key in seenKeys) continue
            seenKeys.add(key)
            parsed.add(
                Source(
                    id = SourceId(0), // replaced by Room on insert
                    type = type,
                    path = path,
                    label = entry.optString("label"),
                    smbConnectionInfo = entry.optJSONObject("smb")?.let { smb ->
                        SmbConnectionInfo(
                            host = smb.getString("host"),
                            shareName = smb.getString("shareName"),
                            remotePath = smb.getString("remotePath"),
                            username = smb.optString("username").ifEmpty { null },
                            // The password is never exported, so an
                            // imported source starts credential-less.
                            hasStoredCredentials = false,
                        )
                    },
                ),
            )
        }
        return parsed
    }

    /**
     * Deletes the video file and its sidecars (thumbnail, .info.json) on
     * disk, then removes the row. The DB row is only removed when every
     * file delete succeeded, so a half-failed delete stays visible and
     * retryable. The next scan reconciles regardless: gone file → row
     * removed as stale.
     */
    fun deleteVideo(video: Video) {
        viewModelScope.launch {
            val source = sourceRepository.getSource(video.sourceId) ?: return@launch
            val fileSystem = fileSystemsByType[source.type] ?: return@launch
            val filesToDelete = buildList {
                add(video.filePath)
                video.thumbnailPath?.let(::add)
                add(video.filePath.substringBeforeLast('.') + ".info.json")
            }.distinct()

            var allDeleted = true
            for (path in filesToDelete) {
                try {
                    fileSystem.deleteFile(source, path)
                } catch (e: Exception) {
                    allDeleted = false
                    Log.e("LocalTubeScan", "Delete failed: '$path' on '${source.label}'", e)
                }
            }
            if (allDeleted) {
                libraryRepository.deleteVideo(video.id)
            }
        }
    }

    class Factory(
        private val appContext: Context,
        private val sourceRepository: SourceRepository,
        private val libraryRepository: LibraryRepository,
        private val selectionState: SelectionState,
        private val fileSystemsByType: Map<SourceType, SourceFileSystem>,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(appContext, sourceRepository, libraryRepository, selectionState, fileSystemsByType) as T
    }
}
