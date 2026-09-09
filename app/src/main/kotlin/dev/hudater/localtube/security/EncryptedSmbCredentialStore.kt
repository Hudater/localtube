package dev.hudater.localtube.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hierynomus.smbj.auth.AuthenticationContext
import dev.hudater.localtube.core.model.Source

/**
 * Stores SMB usernames/passwords in EncryptedSharedPreferences, keyed by
 * source id — kept out of Room entirely (see [dev.hudater.localtube.core.data.db.SourceEntity],
 * which only stores the non-secret `smbUsername` for display purposes,
 * never a password).
 *
 * Rationale: Room's SQLite file is trivially readable if the device is
 * rooted or backed up unencrypted; EncryptedSharedPreferences uses the
 * Android Keystore, which is a meaningfully higher bar for a personal
 * app storing home-server credentials.
 */
class EncryptedSmbCredentialStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "smb_credentials",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(sourceId: Long, username: String, password: String) {
        prefs.edit()
            .putString(usernameKey(sourceId), username)
            .putString(passwordKey(sourceId), password)
            .apply()
    }

    fun clear(sourceId: Long) {
        prefs.edit()
            .remove(usernameKey(sourceId))
            .remove(passwordKey(sourceId))
            .apply()
    }

    fun authenticationFor(source: Source): AuthenticationContext {
        val username = prefs.getString(usernameKey(source.id.value), null)
        val password = prefs.getString(passwordKey(source.id.value), null)

        return if (username != null && password != null) {
            AuthenticationContext(username, password.toCharArray(), null)
        } else {
            // Guest/anonymous fallback for shares that don't require auth.
            AuthenticationContext.anonymous()
        }
    }

    private fun usernameKey(sourceId: Long) = "source_${sourceId}_username"
    private fun passwordKey(sourceId: Long) = "source_${sourceId}_password"
}
