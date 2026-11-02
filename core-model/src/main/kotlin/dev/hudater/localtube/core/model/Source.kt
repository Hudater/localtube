package dev.hudater.localtube.core.model

/**
 * A location the app scans for videos. Not tied to any specific
 * downloader — just a place on disk or on the network that follows the
 * yt-dlp file + sidecar convention (see [dev.hudater.localtube.core.model.RawVideoMetadata]
 * in core-metadata).
 */
data class Source(
    val id: SourceId,
    val type: SourceType,
    /**
     * Meaning depends on [type]:
     * - LOCAL: a SAF tree URI string
     * - SMB: smb://host/share/path style descriptor (host/share/path are
     *   also broken out in [SmbConnectionInfo] when type == SMB)
     * - NFS: export path descriptor
     */
    val path: String,
    val label: String,
    val hidden: Boolean = false,
    /** Non-null ⇔ the last scan of this source failed (null = all clear) — recorded by ScanWorker, not persisted by user action. */
    val lastScanError: String? = null,
    val smbConnectionInfo: SmbConnectionInfo? = null,
)

@JvmInline
value class SourceId(val value: Long)

enum class SourceType {
    LOCAL,
    SMB,
    NFS,
}

/**
 * Connection details for an SMB source. Kept separate from [Source] so
 * that non-SMB sources don't carry null-filled SMB fields.
 */
data class SmbConnectionInfo(
    val host: String,
    val shareName: String,
    val remotePath: String,
    val username: String?,
    /**
     * Stored via Android EncryptedSharedPreferences by the app layer, not
     * in the plain Room row. See docs/ARCHITECTURE.md, "Credential storage".
     */
    val hasStoredCredentials: Boolean,
)
