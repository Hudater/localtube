package dev.hudater.localtube.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.hudater.localtube.core.data.repository.SourceRepository
import dev.hudater.localtube.core.model.Source
import dev.hudater.localtube.core.model.SmbConnectionInfo
import dev.hudater.localtube.core.model.SourceId
import dev.hudater.localtube.core.model.SourceType
import dev.hudater.localtube.security.EncryptedSmbCredentialStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val sourceRepository: SourceRepository,
    private val credentialStore: EncryptedSmbCredentialStore,
) : ViewModel() {

    val sources: StateFlow<List<Source>> =
        sourceRepository.observeAllSources()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addLocalSource(treeUriString: String, label: String) {
        viewModelScope.launch {
            sourceRepository.addSource(
                Source(
                    id = SourceId(0), // replaced by Room on insert
                    type = SourceType.LOCAL,
                    path = treeUriString,
                    label = label,
                )
            )
        }
    }

    fun addSmbSource(
        label: String,
        host: String,
        shareName: String,
        remotePath: String,
        username: String?,
        password: String?,
    ) {
        viewModelScope.launch {
            val added = sourceRepository.addSource(
                Source(
                    id = SourceId(0),
                    type = SourceType.SMB,
                    path = "smb://$host/$shareName/$remotePath",
                    label = label,
                    smbConnectionInfo = SmbConnectionInfo(
                        host = host,
                        shareName = shareName,
                        remotePath = remotePath,
                        username = username,
                        hasStoredCredentials = username != null && password != null,
                    ),
                )
            )
            if (username != null && password != null) {
                credentialStore.save(added.id.value, username, password)
            }
        }
    }

    fun setHidden(source: Source, hidden: Boolean) {
        viewModelScope.launch { sourceRepository.setHidden(source, hidden) }
    }

    fun removeSource(source: Source) {
        viewModelScope.launch {
            credentialStore.clear(source.id.value)
            sourceRepository.removeSource(source)
        }
    }

    class Factory(
        private val sourceRepository: SourceRepository,
        private val credentialStore: EncryptedSmbCredentialStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(sourceRepository, credentialStore) as T
    }
}
