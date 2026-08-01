package bypass.whitelist

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import bypass.whitelist.util.Prefs
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
    }
}
