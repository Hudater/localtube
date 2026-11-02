package dev.hudater.localtube.ui

import dev.hudater.localtube.core.model.ChannelId
import dev.hudater.localtube.core.model.PlaylistId
import dev.hudater.localtube.core.model.Source
import dev.hudater.localtube.core.model.SourceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the source-selector and channel-filter state that the top bar
 * (source dropdown + avatar strip) and the feed/channel screens all read
 * from. Deliberately a plain holder, not a ViewModel — it's shared across
 * screens by living in [dev.hudater.localtube.di.AppContainer]-adjacent
 * scope (constructed once in MainActivity), not because it needs
 * lifecycle awareness of its own.
 */
class SelectionState {

    /** Empty set means "All" — see [isAllSelected]. */
    private val _selectedSourceIds = MutableStateFlow<Set<SourceId>>(emptySet())
    val selectedSourceIds: StateFlow<Set<SourceId>> = _selectedSourceIds.asStateFlow()

    private val _selectedChannelId = MutableStateFlow<ChannelId?>(null)
    val selectedChannelId: StateFlow<ChannelId?> = _selectedChannelId.asStateFlow()

    private val _selectedPlaylistId = MutableStateFlow<PlaylistId?>(null)
    val selectedPlaylistId: StateFlow<PlaylistId?> = _selectedPlaylistId.asStateFlow()

    fun isAllSelected(): Boolean = _selectedSourceIds.value.isEmpty()

    fun selectAll() {
        _selectedSourceIds.value = emptySet()
    }

    fun toggleSource(sourceId: SourceId) {
        val current = _selectedSourceIds.value
        _selectedSourceIds.value = if (sourceId in current) current - sourceId else current + sourceId
    }

    fun selectChannel(channelId: ChannelId?) {
        _selectedChannelId.value = channelId
    }

    fun selectPlaylist(playlistId: PlaylistId?) {
        _selectedPlaylistId.value = playlistId
    }

    /**
     * Resolves the effective source-id list a query should filter by:
     * every visible source when "All" is selected, otherwise exactly the
     * checked ones.
     */
    fun effectiveSourceIds(allVisibleSources: List<Source>): List<SourceId> =
        if (isAllSelected()) {
            allVisibleSources.map { it.id }
        } else {
            _selectedSourceIds.value.toList()
        }
}
