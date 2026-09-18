package com.example.dominocounter.util

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.example.dominocounter.R

/**
 * The language the app is shown in. Backed by AppCompat's per-app locales rather than our own
 * preference: it applies the choice to every screen (recreating the current one), persists it
 * on its own — via the platform on Android 13+, the manifest's AppLocalesMetadataHolderService
 * before that — and stays in step with the system's per-app language setting.
 */
enum class AppLanguage(val tag: String?, @StringRes val label: Int) {
    SYSTEM(null, R.string.settings_language_system),
    ENGLISH("en", R.string.settings_language_english),
    FRENCH("fr", R.string.settings_language_french);

    fun apply() {
        AppCompatDelegate.setApplicationLocales(
            if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
        )
    }

    companion object {
        /** An unsupported or empty override counts as "follow the system". */
        fun current(): AppLanguage {
            val language = AppCompatDelegate.getApplicationLocales()[0]?.language ?: return SYSTEM
            return entries.firstOrNull { it.tag == language } ?: SYSTEM
        }
    }
}
