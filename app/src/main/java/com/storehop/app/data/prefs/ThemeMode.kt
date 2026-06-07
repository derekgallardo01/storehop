package com.storehop.app.data.prefs

/**
 * User's theme preference. SYSTEM follows the OS dark-mode setting; LIGHT and
 * DARK override regardless of system. Persisted as the enum's `name` string in
 * DataStore.
 */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    companion object {
        /** Safe parse: unknown / corrupted values fall back to SYSTEM. */
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}
