package bypass.whitelist.tunnel

import androidx.annotation.StringRes
import bypass.whitelist.R

enum class SplitTunnelingMode(@StringRes val labelRes: Int) {
    NONE(R.string.split_tunneling_mode_off),
    BYPASS(R.string.split_tunneling_mode_bypass),
    ONLY(R.string.split_tunneling_mode_only)
}
