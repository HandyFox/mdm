package com.mdmagent.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.use
import com.google.android.material.materialswitch.MaterialSwitch
import com.mdmagent.R

/**
 * A single settings row: title + optional subtitle + a switch, wrapped in one
 * focusable, clickable surface.
 *
 * Why a custom view: every toggle in the app then behaves identically on BOTH
 * touch and non-touch (D-pad / hardware-key) devices. The whole row is the
 * focus target and the whole row is the hit target — so a user with only a
 * D-pad can land on it and press CENTER to flip it, and a touch user can tap
 * anywhere on it. The switch itself is not independently focusable/clickable,
 * which also sidesteps the classic "programmatic setChecked re-fires the
 * listener" bug: state is pushed in silently, user intent comes only via the
 * row click.
 */
class SettingRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val iconView: ImageView
    private val titleView: TextView
    private val subtitleView: TextView
    private val switchView: MaterialSwitch

    /** Fired only on real user interaction (row tap / D-pad CENTER). */
    var onToggle: ((Boolean) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_setting_row, this, true)

        iconView = findViewById(R.id.row_icon)
        titleView = findViewById(R.id.row_title)
        subtitleView = findViewById(R.id.row_subtitle)
        switchView = findViewById(R.id.row_switch)

        background = ContextCompat.getDrawable(context, R.drawable.bg_setting_row)
        isFocusable = true
        isFocusableInTouchMode = false
        isClickable = true
        minimumHeight = resources.getDimensionPixelSize(R.dimen.row_min_height)
        val h = dp(16); val v = dp(12)
        setPadding(h, v, h, v)

        context.obtainStyledAttributes(attrs, R.styleable.SettingRow).use { a ->
            a.getString(R.styleable.SettingRow_rowTitle)?.let { titleView.text = it }
            setSubtitle(a.getString(R.styleable.SettingRow_rowSubtitle))
            val iconRes = a.getResourceId(R.styleable.SettingRow_rowIcon, 0)
            if (iconRes != 0) setIcon(iconRes)
        }

        setOnClickListener { toggle() }
    }

    fun setTitle(text: CharSequence?) { titleView.text = text }

    fun setSubtitle(text: CharSequence?) {
        if (text.isNullOrBlank()) {
            subtitleView.visibility = GONE
        } else {
            subtitleView.text = text
            subtitleView.visibility = VISIBLE
        }
    }

    fun setIcon(resId: Int) {
        iconView.setImageResource(resId)
        iconView.visibility = VISIBLE
    }

    /** Current switch state. */
    var isChecked: Boolean
        get() = switchView.isChecked
        set(value) { switchView.isChecked = value }   // silent: no onToggle

    /** Flip the switch as if the user did it (fires onToggle). */
    fun toggle() {
        switchView.isChecked = !switchView.isChecked
        onToggle?.invoke(switchView.isChecked)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        isFocusable = enabled
        isClickable = enabled
        switchView.isEnabled = enabled
        alpha = if (enabled) 1f else 0.45f
    }

    /** Convenience: set state + wire the handler in one call. */
    fun bind(checked: Boolean, enabled: Boolean = true, onToggle: (Boolean) -> Unit) {
        isChecked = checked
        isEnabled = enabled
        this.onToggle = onToggle
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
