package bypass.whitelist.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import bypass.whitelist.R
import bypass.whitelist.update.AppUpdate
import bypass.whitelist.update.UpdateDownload
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File

/**
 * What a published release contains, and the two ways out of it.
 *
 * The main button downloads the APK in-app and opens Android's installer on
 * it ([UpdateDownload]). Up to 1.3.18 it handed the URL to the browser
 * instead, on the theory that the browser already has a download manager; in
 * practice the browser's download of a GitHub asset over a Russian line broke
 * off half-way and left a file Android could not open, with no hint as to why.
 *
 * The release page sits beside it because the notes here are cut to what fits
 * a phone, and someone deciding whether to spend those megabytes is entitled
 * to the rest — and because it is the way out when the in-app download fails.
 */
class UpdateActionSheet : BottomSheetDialogFragment() {

    private var update: AppUpdate? = null

    /**
     * Set when this sheet's button started the download: the tap meant
     * "download and install", so the sheet opens the installer the moment the
     * file lands — or the moment the user comes back to it, if they left.
     */
    private var installWhenReady = false

    /** Set when the installer would not open, so the hint can say so instead of looping. */
    private var installerMissing = false

    private var hint: TextView? = null
    private var progress: LinearProgressIndicator? = null
    private var download: MaterialButton? = null

    private val onState: (UpdateDownload.State) -> Unit = { render() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_action_update, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val release = update
        if (release == null) {
            // Only reachable when the process was rebuilt under the sheet, and
            // an empty sheet says less than no sheet.
            dismiss()
            return
        }
        val context = requireContext()
        view.findViewById<TextView>(R.id.sheetTitle).text =
            getString(R.string.update_sheet_title, release.version)
        view.findViewById<TextView>(R.id.sheetSubtitle).text = sizeLabel(context, release)
            ?.let { getString(R.string.update_sheet_subtitle_sized, release.assetName, it) }
            ?: release.assetName
        view.findViewById<TextView>(R.id.updateNotes).text = release.notes.ifBlank {
            getString(R.string.update_sheet_notes_none)
        }

        val page = view.findViewById<MaterialButton>(R.id.buttonPage)
        page.isEnabled = release.pageUrl.isNotEmpty()
        page.setOnClickListener {
            open(release.pageUrl)
            dismiss()
        }
        hint = view.findViewById(R.id.updateHint)
        progress = view.findViewById(R.id.updateProgress)
        download = view.findViewById<MaterialButton>(R.id.buttonDownload).apply {
            setOnClickListener {
                when (val state = UpdateDownload.stateFor(release)) {
                    is UpdateDownload.State.Ready -> installNow(state.file)
                    is UpdateDownload.State.Running -> Unit
                    else -> {
                        installWhenReady = true
                        installerMissing = false
                        UpdateDownload.start(release)
                    }
                }
            }
        }
    }

    // Watching only while visible: a sheet left behind another app must not
    // try to open the installer from the background, where Android blocks it.
    // Unwatched, the download ends in a notification instead — and coming back
    // here picks the finished file up through the first render.
    override fun onStart() {
        super.onStart()
        UpdateDownload.observe(onState)
    }

    override fun onStop() {
        UpdateDownload.forget(onState)
        super.onStop()
    }

    override fun onDestroyView() {
        hint = null
        progress = null
        download = null
        super.onDestroyView()
    }

    private fun render() {
        val release = update ?: return
        val hint = hint ?: return
        val progress = progress ?: return
        val button = download ?: return
        val context = requireContext()
        when (val state = UpdateDownload.stateFor(release)) {
            is UpdateDownload.State.Running -> {
                val total = state.totalBytes
                val sized = total > 0
                if (progress.isIndeterminate == sized) {
                    // Material refuses to switch modes on a visible bar.
                    progress.visibility = View.INVISIBLE
                    progress.isIndeterminate = !sized
                }
                progress.visibility = View.VISIBLE
                if (sized) {
                    val percent = (state.doneBytes * 100 / total).toInt()
                    progress.setProgressCompat(percent, true)
                    button.text = getString(R.string.update_sheet_downloading, percent)
                    hint.text = getString(R.string.update_sheet_hint_running,
                        Formatter.formatShortFileSize(context, state.doneBytes),
                        Formatter.formatShortFileSize(context, total))
                } else {
                    button.text = getString(R.string.update_sheet_downloading_unsized)
                    hint.setText(R.string.update_sheet_hint)
                }
                button.isEnabled = false
            }
            is UpdateDownload.State.Ready -> {
                progress.visibility = View.GONE
                button.isEnabled = true
                button.setText(R.string.update_sheet_install)
                hint.setText(if (installerMissing) R.string.update_sheet_hint_no_installer else R.string.update_sheet_hint)
                if (installWhenReady) installNow(state.file)
            }
            is UpdateDownload.State.Failed -> {
                progress.visibility = View.GONE
                button.isEnabled = true
                button.setText(R.string.update_sheet_retry)
                hint.setText(
                    when (state.reason) {
                        UpdateDownload.Reason.NETWORK -> R.string.update_sheet_hint_failed_network
                        UpdateDownload.Reason.CORRUPT -> R.string.update_sheet_hint_failed_corrupt
                    }
                )
                installWhenReady = false
            }
            UpdateDownload.State.Idle -> {
                progress.visibility = View.GONE
                button.isEnabled = true
                button.setText(R.string.update_sheet_download)
                hint.setText(R.string.update_sheet_hint)
            }
        }
    }

    private fun installNow(file: File) {
        installWhenReady = false
        if (UpdateDownload.install(requireContext(), file)) {
            dismissAllowingStateLoss()
        } else {
            installerMissing = true
            render()
        }
    }

    private fun open(url: String) {
        if (url.isEmpty()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.update_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        fun show(manager: FragmentManager, update: AppUpdate) {
            UpdateActionSheet().apply {
                this.update = update
            }.show(manager, "UpdateActionSheet")
        }

        /**
         * How big the download is, or null when the release did not say.
         *
         * Shared with the rows that offer the sheet: the size is the one fact
         * that decides whether someone on a metered tunnel taps now or later,
         * so it is stated everywhere the update is, in the same words.
         */
        fun sizeLabel(context: Context, update: AppUpdate): String? =
            if (update.sizeBytes > 0L) {
                Formatter.formatShortFileSize(context, update.sizeBytes)
            } else {
                null
            }
    }
}
