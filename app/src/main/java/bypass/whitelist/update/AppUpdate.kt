package bypass.whitelist.update

import org.json.JSONObject

/**
 * A published release newer than the running build, and the file that installs it.
 *
 * The app is sideloaded, so nothing tells a user that a new version exists —
 * until now that was a file sent by hand. This is the whole of what the app
 * needs to say so: what the release is called, what it changed, and where its
 * APK can be fetched from an ordinary browser.
 *
 * Everything here is derived from GitHub's release JSON and nothing else, and
 * all of it is parsed off the main thread by [UpdateCheck].
 */
data class AppUpdate(
    val version: String,
    /** Release notes as written on GitHub, already trimmed; empty when there are none. */
    val notes: String,
    val assetName: String,
    val downloadUrl: String,
    /** Size of the APK; 0 when the release did not say. */
    val sizeBytes: Long,
    /** The release page, so the user can read the rest before spending the megabytes. */
    val pageUrl: String,
) {

    /** One file attached to a release, cut down to the fields acted on here. */
    data class Asset(val name: String, val downloadUrl: String, val sizeBytes: Long)

    companion object {

        /**
         * Reads GitHub's `releases/latest` answer, or null when it names
         * nothing worth showing.
         *
         * Null covers three different "no": the body was not JSON, the tag is
         * not newer than [currentVersion], and the release carries no APK. The
         * last one is a real case — a release can be published before its
         * artifacts finish uploading — and it is treated as no update because
         * the only thing this feature offers is a file to download.
         */
        fun parse(json: String, currentVersion: String): AppUpdate? {
            val release = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val tag = release.optString("tag_name")
            if (!isNewer(tag, currentVersion)) return null
            val version = versionOf(tag)
            val apk = pickApk(assetsIn(release), version) ?: return null
            return AppUpdate(
                version = version,
                notes = trimNotes(release.optString("body")),
                assetName = apk.name,
                downloadUrl = apk.downloadUrl,
                sizeBytes = apk.sizeBytes,
                pageUrl = release.optString("html_url"),
            )
        }

        /**
         * True when [candidate] names a release later than [current].
         *
         * Compared as dotted numbers, never as text: "1.3.10" sorts *before*
         * "1.3.7" as a string, which would hide exactly the update it names.
         * The leading "v" the tags carry means nothing and is dropped.
         *
         * Anything that is not a dotted number — a nightly, a release
         * candidate, an empty tag — cannot be ordered against a version name,
         * and an unorderable tag is reported as "no update" rather than
         * guessed at. Guessing here would either nag every user forever or
         * hide a real release.
         */
        fun isNewer(candidate: String, current: String): Boolean {
            val left = numbersIn(candidate) ?: return false
            val right = numbersIn(current) ?: return false
            for (index in 0 until maxOf(left.size, right.size)) {
                val a = left.getOrElse(index) { 0 }
                val b = right.getOrElse(index) { 0 }
                if (a != b) return a > b
            }
            return false
        }

        /** Tags are written `v1.3.7`; the version name they refer to is not. */
        internal fun versionOf(tag: String): String =
            tag.trim().removePrefix("v").removePrefix("V")

        /**
         * The one file to hand the user out of everything a release carries.
         *
         * A release holds more than the APK — checksums beside it, a mapping
         * file, sometimes a debug build. A debug artifact is worse than a
         * wasted download: it is signed with a different key, so Android
         * refuses to install it over the copy the user already has, and the
         * failure reads as "the update is broken".
         */
        internal fun pickApk(assets: List<Asset>, version: String): Asset? {
            val installable = assets.filter {
                it.name.endsWith(".apk", ignoreCase = true) && !isSideBuild(it.name)
            }
            // More than one signed APK means a re-upload or a leftover from an
            // earlier build; the one named after this release is the one these
            // notes describe.
            return installable.firstOrNull { it.name.contains(version) }
                ?: installable.firstOrNull()
        }

        /**
         * The notes are the reason to update, so they are shown — but a body
         * can run to pages and this ends up in a bottom sheet on a phone. Cut
         * at the last line break that fits, so what is shown ends on a whole
         * line and the release page carries the rest.
         */
        internal fun trimNotes(body: String, limit: Int = NOTES_LIMIT): String {
            val text = body.replace("\r\n", "\n").trim()
            if (text.length <= limit) return text
            val lineEnd = text.lastIndexOf('\n', limit)
            val head = if (lineEnd > limit / 2) text.substring(0, lineEnd) else text.substring(0, limit)
            return head.trimEnd() + "\n…"
        }

        private fun assetsIn(release: JSONObject): List<Asset> {
            val assets = release.optJSONArray("assets") ?: return emptyList()
            return (0 until assets.length()).mapNotNull { index ->
                val asset = assets.optJSONObject(index) ?: return@mapNotNull null
                val url = asset.optString("browser_download_url")
                if (url.isEmpty()) return@mapNotNull null
                Asset(asset.optString("name"), url, asset.optLong("size"))
            }
        }

        private fun isSideBuild(name: String): Boolean {
            val lower = name.lowercase()
            return lower.contains("debug") || lower.contains("unsigned")
        }

        /**
         * The version as numbers, or null when it is not a dotted number.
         *
         * Digits are checked rather than left to [String.toIntOrNull], which
         * accepts "+3" and "-1" and would order a signed nonsense tag happily.
         */
        private fun numbersIn(version: String): List<Int>? {
            val trimmed = versionOf(version)
            if (trimmed.isEmpty()) return null
            return trimmed.split('.').map { part ->
                if (part.isEmpty() || !part.all(Char::isDigit)) return null
                part.toIntOrNull() ?: return null
            }
        }

        private const val NOTES_LIMIT = 600
    }
}
