package com.mdmagent.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mdmagent.R
import com.mdmagent.service.TouchBlockerService
import com.mdmagent.ui.widget.SettingRow
import com.mdmagent.util.DeviceAdminHelper
import com.mdmagent.util.KyoceraOemConfig
import com.mdmagent.util.PolicyPrefs

/**
 * Device-specific controls:
 *   • Touchscreen disable (overlay — works on any device, e.g. LG Wine)
 *   • Screen-lock disable (removes PIN so OEM wipe-on-failed-unlock can't fire)
 *   • Kyocera OEMConfig plugin restrictions (needs the Device Config plugin)
 *
 * Built programmatically so it needs no viewBinding, reusing the shared
 * SettingRow for consistent touch + D-pad behaviour.
 */
class OemConfigActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(color(R.color.bg))
            addView(root)
        })
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) render()
    }

    private fun render() {
        root.removeAllViews()
        title("Kyocera & Device Controls")

        val isOwner = DeviceAdminHelper.isDeviceOwner(this)

        // ── DEVICE ───────────────────────────────────────────────────────
        section("DEVICE")

        addRow(
            "Disable touchscreen (keypad only)",
            "Software block via overlay; hardware keys keep working",
            PolicyPrefs.isTouchBlockEnabled(this)
        ) { want ->
            if (want) enableTouchBlock() else disableTouchBlock()
        }

        addRow(
            "Disable screen lock (no PIN allowed)",
            "Removes the lock screen and stops the OEM wipe-on-failed-unlock",
            DeviceAdminHelper.isScreenLockDisabled(this),
            enabled = isOwner
        ) { want ->
            if (want) {
                val ok = DeviceAdminHelper.disableScreenLock(this)
                if (!ok && !DeviceAdminHelper.isScreenLockDisabled(this)) promptFinishLockRemoval()
            } else DeviceAdminHelper.enableScreenLock(this)
            render()
        }
        if (!isOwner) note("Screen lock control requires Device Owner.", R.color.warn)

        // ── KYOCERA ──────────────────────────────────────────────────────
        section("KYOCERA RESTRICTIONS")
        val pkg = KyoceraOemConfig.installedPluginPackage(this)
        when {
            !isOwner -> note("Requires Device Owner.", R.color.warn)
            pkg == null -> note(
                "Kyocera Device Config plugin not installed. Install " +
                        "jp.kyocera.enterprisedeviceconfig to enable these controls.",
                R.color.text_secondary)
            else -> {
                note("Plugin: $pkg", R.color.success)
                for ((label, key) in KyoceraOemConfig.CURATED_TOGGLES) {
                    val isRecovery = key == "key_restriction_disallow_recovery_mode"
                    addRow(label, key, KyoceraOemConfig.getBooleanRestriction(this, key)) { want ->
                        if (isRecovery && want) confirmRecovery(key)
                        else {
                            val ok = KyoceraOemConfig.setBooleanRestriction(this, key, want)
                            toast(if (ok) "$label = $want" else "Failed to set $label")
                            render()
                        }
                    }
                }
                section("ALL PLUGIN KEYS (reference)")
                for (k in KyoceraOemConfig.listRestrictions(this)) {
                    val state = k.currentBool?.let { "  = $it" } ?: ""
                    keyRow("${k.key} [${k.type}]$state")
                }
            }
        }
    }

    // ── touchscreen ──────────────────────────────────────────────────────
    private fun enableTouchBlock() {
        if (!TouchBlockerService.hasOverlayPermission(this)) {
            AlertDialog.Builder(this)
                .setTitle("Permission needed")
                .setMessage("To disable the touchscreen, allow “Display over other apps” for Device Manager.")
                .setPositiveButton("Open settings") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")))
                    } catch (_: Exception) {}
                }
                .setNegativeButton("Cancel", null)
                .setOnDismissListener { render() }
                .show()
            return
        }
        PolicyPrefs.setTouchBlockEnabled(this, true)
        TouchBlockerService.start(this)
        toast("Touchscreen disabled — use the keypad")
    }

    private fun disableTouchBlock() {
        PolicyPrefs.setTouchBlockEnabled(this, false)
        TouchBlockerService.stop(this)
        toast("Touchscreen re-enabled")
    }

    private fun promptFinishLockRemoval() {
        AlertDialog.Builder(this)
            .setTitle("One more step")
            .setMessage("Password rules were cleared, so “None” is now selectable.\n\n" +
                    "Android won't let an app delete an existing PIN, so finish here:\n" +
                    "Settings > Security > Screen lock > None")
            .setPositiveButton("Open settings") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } catch (_: Exception) {}
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun confirmRecovery(key: String) {
        AlertDialog.Builder(this)
            .setTitle("Disable Recovery Mode?")
            .setMessage("Blocks the Volume+Power recovery / factory-reset menu.\n\n" +
                    "This also removes your unbrick path. If a device wedges with recovery " +
                    "off, it's a return-to-Kyocera situation.\n\nProceed?")
            .setPositiveButton("Disable Recovery") { _, _ ->
                val ok = KyoceraOemConfig.setBooleanRestriction(this, key, true)
                toast(if (ok) "Recovery disabled" else "Failed")
                render()
            }
            .setNegativeButton("Cancel") { _, _ -> render() }
            .show()
    }

    // ── view helpers ─────────────────────────────────────────────────────
    private fun addRow(
        title: String, subtitle: String, isOn: Boolean,
        enabled: Boolean = true, onToggle: (Boolean) -> Unit
    ) {
        val row = SettingRow(this).apply {
            setTitle(title); setSubtitle(subtitle)
            bind(isOn, enabled, onToggle)
        }
        root.addView(row, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).let { LinearLayout.LayoutParams(it).apply { topMargin = dp(8) } })
    }

    private fun title(t: String) = root.addView(TextView(this).apply {
        text = t; setTextColor(color(R.color.text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(2), 0, 0, dp(6))
    })

    private fun section(t: String) = root.addView(TextView(this).apply {
        text = t; setTextColor(color(R.color.text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        letterSpacing = 0.12f
        setPadding(dp(2), dp(18), 0, dp(6))
    })

    private fun note(t: String, colorRes: Int) = root.addView(TextView(this).apply {
        text = t; setTextColor(color(colorRes))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(2), dp(4), dp(2), dp(4))
    })

    private fun keyRow(mono: String) = root.addView(TextView(this).apply {
        text = mono; setTextColor(color(R.color.text_hint))
        typeface = android.graphics.Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setPadding(dp(2), dp(4), 0, 0)
    })

    private fun color(res: Int) = ContextCompat.getColor(this, res)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
