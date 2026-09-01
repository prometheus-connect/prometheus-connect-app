package bypass.whitelist

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import bypass.whitelist.util.Prefs
import androidx.core.os.LocaleListCompat
import bypass.whitelist.util.LanguageMode
import bypass.whitelist.util.ThemeMode

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        applyTheme(Prefs.themeMode)
    }

    companion object {
        @JvmStatic
        lateinit var instance: Context
            private set

        fun applyTheme(mode: ThemeMode) {
            val target = when (mode) {
                ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            }
            AppCompatDelegate.setDefaultNightMode(target)
        }

        /**
         * Reads back what the app is actually set to, rather than a preference
         * of our own. AppCompat already persists the choice (autoStoreLocales
         * in the manifest), so keeping a second copy in Prefs would only give
         * the two a chance to disagree after a system-side change.
         */
        fun currentLanguage(): LanguageMode {
            val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            return LanguageMode.entries.firstOrNull {
                it.tag.isNotEmpty() && tag.startsWith(it.tag, ignoreCase = true)
            } ?: LanguageMode.SYSTEM
        }

        fun applyLanguage(mode: LanguageMode) {
            AppCompatDelegate.setApplicationLocales(
                if (mode.tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                else LocaleListCompat.forLanguageTags(mode.tag)
            )
        }
    }
}
