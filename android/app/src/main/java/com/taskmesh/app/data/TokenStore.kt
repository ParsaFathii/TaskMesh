package com.taskmesh.app.data

import android.content.Context

/**
 * Persisted client state (SharedPreferences "taskmesh_prefs"):
 * the JWT, the last API base URL, the notification preference and the last
 * known user (JSON) so a process restart can restore the session without a
 * network round-trip.
 *
 * The interface keeps the rest of the data layer pure JVM (unit-testable);
 * [SharedPrefsTokenStore] is the only Android-flavored class in `data/`.
 */
interface TokenStore {
    /** Bearer JWT; null when logged out. */
    var token: String?

    /** Configured API origin (e.g. "http://10.0.2.2:8080"); null → default. */
    var baseUrl: String?

    /** Whether detected events are posted as system notifications. */
    var notificationsEnabled: Boolean

    /** Serialized [UserBriefDto] of the last successful login; null when none. */
    var cachedUserJson: String?

    /** Drops session data (token + cached user); keeps base URL + preferences. */
    fun clearSession()
}

class SharedPrefsTokenStore(context: Context) : TokenStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) {
            if (value == null) {
                prefs.edit().remove(KEY_TOKEN).apply()
            } else {
                prefs.edit().putString(KEY_TOKEN, value).apply()
            }
        }

    override var baseUrl: String?
        get() = prefs.getString(KEY_BASE_URL, null)
        set(value) {
            if (value == null) {
                prefs.edit().remove(KEY_BASE_URL).apply()
            } else {
                prefs.edit().putString(KEY_BASE_URL, value).apply()
            }
        }

    override var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_NOTIFICATIONS, value).apply()
        }

    override var cachedUserJson: String?
        get() = prefs.getString(KEY_USER, null)
        set(value) {
            if (value == null) {
                prefs.edit().remove(KEY_USER).apply()
            } else {
                prefs.edit().putString(KEY_USER, value).apply()
            }
        }

    override fun clearSession() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "taskmesh_prefs"
        private const val KEY_TOKEN = "token"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_NOTIFICATIONS = "notifications_enabled"
        private const val KEY_USER = "cached_user"
    }
}

/** Restores the cached user brief from its JSON form; null when unparsable/absent. */
fun parseCachedUser(text: String?): UserBriefDto? {
    if (text.isNullOrBlank()) {
        return null
    }
    return runCatching {
        JsonConfig.json.decodeFromString(UserBriefDto.serializer(), text)
    }.getOrNull()
}
