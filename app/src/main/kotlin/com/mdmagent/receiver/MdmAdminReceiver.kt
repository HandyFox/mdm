package com.mdmagent.receiver

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.os.UserManager
import android.widget.Toast
import com.mdmagent.ui.ProvisioningCompleteActivity

class MdmAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(context, "Device Manager activated", Toast.LENGTH_LONG).show()
    }

    /**
     * Called when provisioning via QR code completes.
     * At this point the app is already Device Owner.
     * Apply all lockdowns immediately and launch setup UI.
     */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = getWho(context)

        // ── Apply core lockdowns the moment Device Owner is granted ──────────
        // Block factory reset — prevents user wiping device to escape
        dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
        // Block safe boot — prevents booting without policies
        dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
        // Block ADB / developer options
        dpm.addUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)
        // Block installing unknown APKs
        dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)

        // Launch the welcome/setup screen
        val setupIntent = Intent(context, ProvisioningCompleteActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(setupIntent)
    }

    /**
     * As Device Owner this is only triggered if someone somehow reaches
     * Settings → Security → Device Admin. Return a strong warning.
     * The button will be fully greyed out for Device Owner apps —
     * this is an extra safety net.
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "⛔ This device is managed. Open the Device Manager app and use " +
               "Settings → Unenroll Device to remove management (administrator password required)."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(context, "Device Manager deactivated", Toast.LENGTH_LONG).show()
    }

    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) { }
    override fun onPasswordChanged(context: Context, intent: Intent, user: UserHandle) { }

    companion object {
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, MdmAdminReceiver::class.java)
        }
    }
}
