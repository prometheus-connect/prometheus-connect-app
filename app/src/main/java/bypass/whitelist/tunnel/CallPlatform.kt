package bypass.whitelist.tunnel

enum class CallPlatform(val id: String, val urlMarker: String) {
    VK("vk", ""),
    TELEMOST("telemost", "telemost"),
    WBSTREAM("wbstream", "wbstream://"),
    DION("dion", "dion://");

    companion object {
        // A WB Stream call arrives in two shapes. The pool publishes the compact
        // wbstream://<id> form, but a link copied out of a browser — or saved from
        // the "paste a call link" card — is https://stream.wb.ru/room/<id>. Only
        // the first was recognised, so a saved web link fell through to `else ->
        // VK`, which skips the headless path in MainActivity and opens the meeting
        // in a WebView with the VK autoclicker attached. Its selectors match
        // nothing on WB's page, so the join dialog sits there untouched and the
        // relay loops on "Pion relay disconnected, reconnecting".
        private const val WB_WEB_HOST = "stream.wb.ru"
        private const val WB_WEB_ROOM = "stream.wb.ru/room/"

        fun fromUrl(url: String): CallPlatform = when {
            url.contains(DION.urlMarker) || url.contains("dion.vc/event/") -> DION
            url.contains(WBSTREAM.urlMarker) || url.contains(WB_WEB_HOST) -> WBSTREAM
            url.contains(TELEMOST.urlMarker) -> TELEMOST
            else -> VK
        }

        fun extractRoomId(url: String): String {
            val trimmed = url.trim()
            if (trimmed.startsWith(WBSTREAM.urlMarker)) return trimmed.removePrefix(WBSTREAM.urlMarker).trim()
            if (trimmed.startsWith(DION.urlMarker)) return trimmed.removePrefix(DION.urlMarker).trim()
            val wbIdx = trimmed.indexOf(WB_WEB_ROOM)
            if (wbIdx >= 0) {
                var slug = trimmed.substring(wbIdx + WB_WEB_ROOM.length)
                val q = slug.indexOf('?')
                if (q >= 0) slug = slug.substring(0, q)
                val s = slug.indexOf('/')
                if (s >= 0) slug = slug.substring(0, s)
                return slug.trim()
            }
            val dionPrefix = "dion.vc/event/"
            val idx = trimmed.indexOf(dionPrefix)
            if (idx >= 0) {
                var slug = trimmed.substring(idx + dionPrefix.length)
                val q = slug.indexOf('?')
                if (q >= 0) slug = slug.substring(0, q)
                val s = slug.indexOf('/')
                if (s >= 0) slug = slug.substring(0, s)
                return slug.trim()
            }
            return trimmed
        }
    }
}
