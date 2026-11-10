package com.mdmagent.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mdmagent.service.MdmVpnService
import com.mdmagent.service.TouchBlockerService
import com.mdmagent.util.PolicyPrefs

/**
 * Re-applies the enforcement services after reboot. Device-policy restrictions
 * (camera, factory reset, hotspot, etc.) are persisted by the system and need
 * no re-applying; only our two services do.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        if (PolicyPrefs.isWhitelistEnabled(context)) {
            try { MdmVpnService.start(context) } catch (_: Exception) {}
        }
        if (PolicyPrefs.isTouchBlockEnabled(context) &&
            TouchBlockerService.hasOverlayPermission(context)) {
            try { TouchBlockerService.start(context) } catch (_: Exception) {}
        }
    }
}
