package com.mdmagent.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.mdmagent.util.DeviceAdminHelper

/**
 * Blocks the user from setting a screen-lock PIN/password.
 *
 * Registered in the manifest as a HIGH-PRIORITY handler for the system intent
 * android.app.action.SET_NEW_PASSWORD (fired by Settings when the user taps
 * "Set screen lock" / "Set a PIN"). Because this activity outranks the Settings
 * lock-picker, it opens instead, warns, and immediately finishes — so the user
 * is bounced back out and the PIN screen never appears.
 *
 * Only actually blocks when the MDM screen-lock lockdown is engaged; otherwise
 * it forwards to the real Settings picker so normal behaviour is preserved when
 * the admin hasn't turned the restriction on.
 */
class ScreenLockBlockerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val blocking = try {
            DeviceAdminHelper.isDeviceOwner(this) &&
                DeviceAdminHelper.isScreenLockDisabled(this)
        } catch (e: Exception) {
            false
        }

        if (blocking) {
            Toast.makeText(
                this,
                "\u26a0\ufe0f Screen lock changes are disabled by your administrator",
                Toast.LENGTH_LONG
            ).show()
            // Send the user Home so they leave the Settings flow entirely.
            try {
                val home = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                    addCategory(android.content.Intent.CATEGORY_HOME)
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(home)
            } catch (_: Exception) {
            }
            finish()
        } else {
            // Not locking down — hand off to the real system lock-screen picker.
            try {
                val real = android.content.Intent("android.app.action.SET_NEW_PASSWORD")
                    .setPackage("com.android.settings")
                startActivity(real)
            } catch (_: Exception) {
            }
            finish()
        }
    }
}
