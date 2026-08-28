package bypass.whitelist.routing

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** A `geosite:`/`geoip:` name as the catalogue files it. */
data class CategoryName(val kind: RuleKind, val name: String) {

    /** The publisher's folder for this half of the catalogue. */
    internal val section: String get() = if (kind == RuleKind.GEOIP) "ip" else "site"
}

/** A category resolved to its rules, or the reason there are none to hand. */
class CategoryLookup(val blob: ByteArray?, val status: RuleStatus)

/**
 * Where [UserRules] gets the contents of a category name.
 *
 * An interface rather than a call into [RuleCatalogue] so the overlay can be
 * built and tested without a filesystem or a network, and so the screen can ask
 * the same question offline that the service asks online.
 */
fun interface CategorySource {

    fun lookup(kind: RuleKind, name: String): CategoryLookup

    companion object {
        /** Nothing resolves. Every category reads as "not downloaded yet". */
        val NONE = CategorySource { _, _ -> CategoryLookup(null, RuleStatus.NOT_DOWNLOADED) }
    }
}

/**
 * The published category catalogue, cached per revision.
 *
 * [RuleSet] loads one compiled profile; a user rule naming `geosite:2ch` needs
 * that category on its own, which the publisher ships two ways. Ordinary
 * categories travel together in `site-bundle.bin` / `ip-bundle.bin`, and the
 * handful too large to sit in a bundle get a file of their own. So resolving a
 * name is: manifest first, bundle if the manifest says it is in one, standalone
 * file otherwise.
 *
 * Everything wanted is resolved in a single pass, because the bundles are
 * megabytes and the cost is per download, not per name — five categories out of
 * one bundle is one download.
 *
 * Payloads are cached under the manifest revision they came from, so a rebuild
 * upstream invalidates the lot by writing to a different directory. The old
 * directory is deleted rather than kept: a category mixed from two revisions
 * would route by rules that never existed together.
 *
 * A name that cannot be resolved is reported, never guessed. The router treats
 * an unmatched destination as "tunnel", so a category that quietly resolves to
 * nothing costs speed; one that quietly resolves to the wrong thing costs
 * cover, and the difference is the whole reason the status is carried out.
 */
object RuleCatalogue {

    /**
     * @param allowNetwork false answers from what is already on disk. The
     * screen opens with it off, because opening a screen must not cost the user
     * megabytes on a metered connection; the service turns it on.
     *
     * Never call this on the main thread with [allowNetwork] on.
     */
    fun resolve(
        context: Context,
        publicKey: String,
        wanted: Set<CategoryName>,
        allowNetwork: Boolean,
    ): CategorySource {
        if (wanted.isEmpty()) return CategorySource.NONE
        val home = File(context.filesDir, DIR)
        val manifest = readManifest(home, publicKey, allowNetwork) ?: return CategorySource.NONE
        val store = File(home, manifest.revision)
        prune(home, keep = manifest.revision)

        val found = HashMap<CategoryName, CategoryLookup>()
        val missing = ArrayList<CategoryName>()
        for (category in wanted) {
            val entry = manifest.entry(category)
            when {
                entry == null -> found[category] = CategoryLookup(null, RuleStatus.UNKNOWN_CATEGORY)
                entry.unavailable -> found[category] = CategoryLookup(null, RuleStatus.UNAVAILABLE_CATEGORY)
                else -> {
                    val cached = cacheFile(store, category)
                    if (cached.isFile) {
                        found[category] = CategoryLookup(cached.readBytes(), RuleStatus.ACTIVE)
                    } else {
                        missing += category
                    }
                }
            }
        }
        if (missing.isNotEmpty() && allowNetwork) download(publicKey, manifest, store, missing, found)
        for (category in missing) {
            found.getOrPut(category) { CategoryLookup(null, RuleStatus.NOT_DOWNLOADED) }
        }
        return CategorySource { kind, name ->
            found[CategoryName(kind, name)] ?: CategoryLookup(null, RuleStatus.NOT_DOWNLOADED)
        }
    }

    // ---- fetching ---------------------------------------------------------

    private fun download(
        publicKey: String,
        manifest: Manifest,
        store: File,
        missing: List<CategoryName>,
        into: MutableMap<CategoryName, CategoryLookup>,
    ) {
        if (!store.isDirectory && !store.mkdirs()) {
            Log.w(TAG, "cannot create $store")
            return
        }
        for (section in missing.map { it.section }.distinct()) {
            val wanted = missing.filter { it.section == section }
            val bundled = wanted.filter { manifest.entry(it)?.standalone != true }
            if (bundled.isNotEmpty()) {
                runCatching {
                    val bundle = PublicDisk.read(publicKey, "/$section-bundle.bin")
                    extract(bundle, bundled.map { it.name }.toSet())
                }.onSuccess { payloads ->
                    for (category in bundled) {
                        val payload = payloads[category.name] ?: continue
                        keep(store, category, payload, into)
                    }
                }.onFailure { Log.w(TAG, "$section bundle not fetched: ${it.message}") }
            }
            for (category in wanted - bundled.toSet()) {
                runCatching { PublicDisk.read(publicKey, "/$section/${category.name}.bin") }
                    .onSuccess { keep(store, category, it, into) }
                    .onFailure { Log.w(TAG, "${category.name} not fetched: ${it.message}") }
            }
        }
    }

    /** A payload only counts once it is on disk, so the next start need not fetch it. */
    private fun keep(
        store: File,
        category: CategoryName,
        payload: ByteArray,
        into: MutableMap<CategoryName, CategoryLookup>,
    ) {
        val target = cacheFile(store, category)
        runCatching {
            val tmp = File(store, target.name + ".tmp")
            tmp.writeBytes(payload)
            if (!tmp.renameTo(target)) throw IllegalStateException("cannot replace ${target.name}")
        }.onFailure { Log.w(TAG, "${category.name} not cached: ${it.message}") }
        into[category] = CategoryLookup(payload, RuleStatus.ACTIVE)
    }

    /**
     * Pulls named payloads out of a `PCBN` bundle: magic, version, three pad
     * bytes, a count, then length-prefixed name and payload per entry.
     *
     * Walks rather than indexes. The bundle is read once and thrown away, so
     * building a table of 1500 offsets to use five of them would be work for
     * nothing.
     */
    internal fun extract(bundle: ByteArray, names: Set<String>): Map<String, ByteArray> {
        require(bundle.size >= 12) { "bundle too short" }
        require(bundle.copyOfRange(0, 4).contentEquals(BUNDLE_MAGIC)) { "bad bundle magic" }
        require(bundle[4].toInt() == 1) { "unsupported bundle format ${bundle[4]}" }
        val count = ByteBuffer.wrap(bundle, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val out = HashMap<String, ByteArray>(names.size)
        var offset = 12
        repeat(count) {
            val nameLength = ByteBuffer.wrap(bundle, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            offset += 2
            val name = String(bundle, offset, nameLength, StandardCharsets.UTF_8)
            offset += nameLength
            val payloadLength = ByteBuffer.wrap(bundle, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
            offset += 4
            if (name in names) out[name] = bundle.copyOfRange(offset, offset + payloadLength)
            offset += payloadLength
        }
        return out
    }

    // ---- manifest ---------------------------------------------------------

    private fun readManifest(home: File, publicKey: String, allowNetwork: Boolean): Manifest? {
        val file = File(home, MANIFEST)
        if (allowNetwork) {
            runCatching {
                val bytes = PublicDisk.read(publicKey, "/$MANIFEST", readTimeoutMs = 30_000)
                Manifest.parse(bytes)  // never replace a readable copy with something unreadable
                home.mkdirs()
                val tmp = File(home, "$MANIFEST.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(file)) throw IllegalStateException("cannot replace $MANIFEST")
            }.onFailure { Log.w(TAG, "catalogue manifest refresh skipped: ${it.message}") }
        }
        if (!file.isFile) return null
        return runCatching { Manifest.parse(file.readBytes()) }
            .onFailure { Log.w(TAG, "catalogue manifest unreadable: ${it.message}") }
            .getOrNull()
    }

    private class Manifest(val revision: String, private val sections: Map<String, Map<String, Entry>>) {

        class Entry(val standalone: Boolean, val unavailable: Boolean, val count: Int)

        fun entry(category: CategoryName): Entry? = sections[category.section]?.get(category.name)

        companion object {
            fun parse(bytes: ByteArray): Manifest {
                val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
                // The revision names a directory, so it may only be what a
                // directory name may be — it comes off the network.
                val revision = json.optString("revision")
                    .filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
                if (revision.isEmpty()) throw IllegalStateException("manifest has no revision")
                val catalogue = json.optJSONObject("catalogue")
                    ?: throw IllegalStateException("manifest has no catalogue")
                val sections = HashMap<String, Map<String, Entry>>()
                for (section in catalogue.keys()) {
                    val listed = catalogue.optJSONObject(section) ?: continue
                    val entries = HashMap<String, Entry>()
                    for (name in listed.keys()) {
                        val fields = listed.optJSONObject(name) ?: continue
                        entries[name] = Entry(
                            standalone = fields.optBoolean("standalone", false),
                            unavailable = fields.optBoolean("unavailable", false),
                            count = fields.optInt("count", 0),
                        )
                    }
                    sections[section] = entries
                }
                return Manifest(revision, sections)
            }
        }
    }

    // ---- cache ------------------------------------------------------------

    private fun cacheFile(store: File, category: CategoryName): File =
        File(store, "${category.section}-${category.name}.bin")

    private fun prune(home: File, keep: String) {
        val stale = home.listFiles()?.filter { it.isDirectory && it.name != keep } ?: return
        stale.forEach { it.deleteRecursively() }
    }

    private const val TAG = "RuleCatalogue"
    private const val DIR = "routing-categories"
    private const val MANIFEST = "manifest.json"
    private val BUNDLE_MAGIC = byteArrayOf('P'.code.toByte(), 'C'.code.toByte(),
        'B'.code.toByte(), 'N'.code.toByte())
}
