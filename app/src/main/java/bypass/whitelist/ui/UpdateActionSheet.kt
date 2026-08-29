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
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton

/**
 * What a published release contains, and the two ways out of it.
 *
 * The download button hands the APK's URL to the system browser and stops
 * there. Fetching it in-process would mean asking for REQUEST_INSTALL_PACKAGES
 * and carrying 34 MB over a link that is often 4 Mbit/s and drops — a download
 * manager's job, not this sheet's. The browser already has one.
 *
 * The release page sits beside it because the notes here are cut to what fits
 * a phone, and someone deciding whether to spend those megabytes is entitled
 * to the rest.
 */
class UpdateActionSheet : BottomSheetDialogFragment() {

    private var update: AppUpdate? = null

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
        view.findViewById<MaterialButton>(R.id.buttonDownload).setOnClickListener {
            open(release.downloadUrl)
            dismiss()
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
