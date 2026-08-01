package cc.cors.connect.cors

import bypass.whitelist.App

/** Tiny indirection so background workers can resolve string resources by name. */
object AppRes {
    /** Returns the (unformatted) string for [name], or [name] itself if missing. */
    fun string(name: String): String {
        val res = App.instance.resources
        val id = res.getIdentifier(name, "string", App.instance.packageName)
        return if (id != 0) res.getString(id) else name
    }
}
