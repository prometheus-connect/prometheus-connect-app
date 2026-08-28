package bypass.whitelist.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import bypass.whitelist.R
import bypass.whitelist.util.ParamCallback
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton

/**
 * One rule list, edited as plain text.
 *
 * [InputActionSheet] cannot stand in for this: it is single-line and it refuses
 * to save an empty value, and emptying a routing list is a thing a user is
 * entitled to do.
 *
 * Opens read-only when the list cannot be enforced. A field that takes edits
 * nothing will act on is a lie told in the most convincing way available, so
 * when the router will not run, the box shows the rules and the reason and
 * offers no way to change them.
 */
class RuleListActionSheet : BottomSheetDialogFragment() {

    private var titleText: String = ""
    private var subtitleText: String = ""
    private var initialValue: String = ""
    private var readOnly: Boolean = false
    private var onSave: ParamCallback<String>? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_action_rules, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<TextView>(R.id.sheetTitle).text = titleText
        view.findViewById<TextView>(R.id.sheetSubtitle).text = subtitleText

        val input = view.findViewById<EditText>(R.id.rulesField)
        input.setText(initialValue)
        input.setSelection(input.text.length)

        val cancel = view.findViewById<MaterialButton>(R.id.buttonCancel)
        val save = view.findViewById<MaterialButton>(R.id.buttonSave)
        cancel.setOnClickListener { dismiss() }
        if (readOnly) {
            // Dropping the key listener rather than disabling the field: the
            // rules stay readable, scrollable and copyable, and only the typing
            // goes away. A greyed-out box would hide the very list the user
            // came to check.
            input.keyListener = null
            input.isCursorVisible = false
            save.visibility = View.GONE
            cancel.setText(R.string.sheet_close)
        } else {
            save.setOnClickListener {
                onSave?.invoke(input.text.toString())
                dismiss()
            }
        }
    }

    companion object {
        fun show(
            manager: FragmentManager,
            title: String,
            subtitle: String,
            initialValue: String,
            readOnly: Boolean = false,
            onSave: ParamCallback<String>,
        ) {
            RuleListActionSheet().apply {
                this.titleText = title
                this.subtitleText = subtitle
                this.initialValue = initialValue
                this.readOnly = readOnly
                this.onSave = onSave
            }.show(manager, "RuleListActionSheet")
        }
    }
}
