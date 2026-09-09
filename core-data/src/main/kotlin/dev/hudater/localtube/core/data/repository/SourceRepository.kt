package dev.hudater.localtube.core.data.repository

import dev.hudater.localtube.core.data.db.SourceDao
import dev.hudater.localtube.core.data.db.toDomain
import dev.hudater.localtube.core.data.db.toEntity
import dev.hudater.localtube.core.model.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The only way the UI/ViewModel layer touches source data — never Room
 * directly. Swapping this implementation (e.g. for a remote-API-backed
 * one in a hypothetical web client) is the intended extension point.
 */
interface SourceRepository {
    fun observeAllSources(): Flow<List<Source>>
    fun observeVisibleSources(): Flow<List<Source>>
    suspend fun addSource(source: Source): Source
    suspend fun updateSource(source: Source)
    suspend fun removeSource(source: Source)
    suspend fun setHidden(source: Source, hidden: Boolean)
}

class RoomSourceRepository(
    private val sourceDao: SourceDao,
) : SourceRepository {

    override fun observeAllSources(): Flow<List<Source>> =
        sourceDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeVisibleSources(): Flow<List<Source>> =
        sourceDao.observeVisible().map { list -> list.map { it.toDomain() } }

    override suspend fun addSource(source: Source): Source {
        val newId = sourceDao.upsert(source.toEntity())
        return source.copy(id = dev.hudater.localtube.core.model.SourceId(newId))
    }

    override suspend fun updateSource(source: Source) {
        sourceDao.update(source.toEntity())
    }

    override suspend fun removeSource(source: Source) {
        sourceDao.delete(source.toEntity())
    }

    override suspend fun setHidden(source: Source, hidden: Boolean) {
        sourceDao.update(source.toEntity().copy(hidden = hidden))
    }
}
