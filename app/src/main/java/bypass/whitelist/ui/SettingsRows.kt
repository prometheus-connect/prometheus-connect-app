package bypass.whitelist.ui

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isNotEmpty
import androidx.fragment.app.Fragment
import bypass.whitelist.R
import bypass.whitelist.util.Callback
import bypass.whitelist.util.ParamCallback
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * The card-and-row vocabulary the settings screens are built from.
 *
 * Lifted out of [SettingsScreenFragment] when a second screen needed the same
 * rows: two copies of this would drift, and the drift would show up as one
 * screen's dividers and icon boxes not quite matching the other's.
 */
object SettingsRows {

    fun newSection(fragment: Fragment, parent: ViewGroup?, labelRes: Int): View {
        val view = fragment.layoutInflater.inflate(R.layout.item_settings_section, parent, false)
        view.findViewById<TextView>(R.id.sectionLabel).setText(labelRes)
        view.findViewById<View>(R.id.sectionCard).clipToOutline = true
        return view
    }

    fun card(section: View): LinearLayout = section.findViewById(R.id.sectionCard)

    fun addRow(
        fragment: Fragment,
        card: LinearLayout,
        iconRes: Int,
        title: String,
        sub: String?,
        trail: String?,
        danger: Boolean = false,
        onClick: Callback,
    ) {
        val row = inflateRow(fragment, card, iconRes, title, sub, trail)
        if (danger) {
            val context = fragment.requireContext()
            row.findViewById<View>(R.id.rowIconBox).setBackgroundResource(R.drawable.bg_settings_row_icon_danger)
            row.findViewById<ImageView>(R.id.rowIcon).setColorFilter(context.getColor(R.color.error_red))
            row.findViewById<TextView>(R.id.rowTitle).setTextColor(context.getColor(R.color.error_red))
        }
        row.findViewById<ImageView>(R.id.rowChev).visibility = View.VISIBLE
        row.setOnClickListener { onClick() }
        attach(fragment, card, row)
    }

    /**
     * A row that only reports. No chevron and no click target, so nothing
     * invites a tap that would do nothing.
     */
    fun addInfoRow(
        fragment: Fragment,
        card: LinearLayout,
        iconRes: Int,
        title: String,
        sub: String?,
        trail: String?,
    ) {
        val row = inflateRow(fragment, card, iconRes, title, sub, trail)
        row.isClickable = false
        row.isFocusable = false
        row.background = null
        attach(fragment, card, row)
    }

    fun addSwitchRow(
        fragment: Fragment,
        card: LinearLayout,
        iconRes: Int,
        title: String,
        sub: String?,
        initial: Boolean,
        onToggled: ParamCallback<Boolean>,
    ) {
        val row = inflateRow(fragment, card, iconRes, title, sub, null)
        val switch = row.findViewById<MaterialSwitch>(R.id.rowSwitch)
        switch.visibility = View.VISIBLE
        switch.isChecked = initial
        row.setOnClickListener {
            switch.isChecked = !switch.isChecked
            onToggled(switch.isChecked)
        }
        attach(fragment, card, row)
    }

    private fun inflateRow(
        fragment: Fragment,
        card: LinearLayout,
        iconRes: Int,
        title: String,
        sub: String?,
        trail: String?,
    ): View {
        val row = fragment.layoutInflater.inflate(R.layout.item_settings_row, card, false)
        row.findViewById<ImageView>(R.id.rowIcon).setImageResource(iconRes)
        row.findViewById<TextView>(R.id.rowTitle).text = title
        row.findViewById<TextView>(R.id.rowSub).apply {
            if (sub.isNullOrBlank()) { visibility = View.GONE } else { text = sub; visibility = View.VISIBLE }
        }
        row.findViewById<TextView>(R.id.rowTrail).apply {
            if (trail.isNullOrBlank()) { visibility = View.GONE } else { text = trail; visibility = View.VISIBLE }
        }
        return row
    }

    private fun attach(fragment: Fragment, card: LinearLayout, row: View) {
        if (card.isNotEmpty()) addDividerTo(fragment, card)
        card.addView(row)
    }

    private fun addDividerTo(fragment: Fragment, card: LinearLayout) {
        val context = fragment.requireContext()
        val inset = (14 * context.resources.displayMetrics.density).toInt()
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                marginStart = inset
                marginEnd = inset
            }
            setBackgroundColor(context.getColor(R.color.hair))
        }
        card.addView(divider)
    }
}
