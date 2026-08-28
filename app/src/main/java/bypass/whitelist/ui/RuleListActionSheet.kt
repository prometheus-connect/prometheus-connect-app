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
 */
class RuleListActionSheet : BottomSheetDialogFragment() {

    private var titleText: String = ""
    private var subtitleText: String = ""
    private var initialValue: String = ""
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

        view.findViewById<MaterialButton>(R.id.buttonCancel).setOnClickListener { dismiss() }
        view.findViewById<MaterialButton>(R.id.buttonSave).setOnClickListener {
            onSave?.invoke(input.text.toString())
            dismiss()
        }
    }

    companion object {
        fun show(
            manager: FragmentManager,
            title: String,
            subtitle: String,
            initialValue: String,
            onSave: ParamCallback<String>,
        ) {
            RuleListActionSheet().apply {
                this.titleText = title
                this.subtitleText = subtitle
                this.initialValue = initialValue
                this.onSave = onSave
            }.show(manager, "RuleListActionSheet")
        }
    }
}
