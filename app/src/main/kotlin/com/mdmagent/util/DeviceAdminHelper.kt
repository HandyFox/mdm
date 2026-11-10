package com.mdmagent.util

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log

/**
 * Central helper for all DevicePolicyManager operations.
 *
 * Web filtering is NO LONGER done here (the old global-proxy "proxy bridge"
 * was removed). Domain whitelisting is now enforced by [com.mdmagent.vpn]
 * via a local VpnService, so this file is purely device-policy controls.
 */
object DeviceAdminHelper {

    private const val TAG = "DeviceAdminHelper"

    /** A deliberately huge failed-attempt threshold — effectively "never wipe". */
    const val WIPE_GUARD_ATTEMPTS = 9999

    fun getDpm(context: Context): DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun getAdmin(context: Context): ComponentName =
        ComponentName(context, com.mdmagent.receiver.MdmAdminReceiver::class.java)

    fun isAdminActive(context: Context): Boolean =
        getDpm(context).isAdminActive(getAdmin(context))

    fun isDeviceOwner(context: Context): Boolean =
        getDpm(context).isDeviceOwnerApp(context.packageName)

    // ─── App Installation Blocking ────────────────────────────────────────────

    fun blockAllAppInstallation(context: Context) {
        val dpm = getDpm(context); val admin = getAdmin(context)
        if (isDeviceOwner(context)) {
            dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        }
    }

    fun allowAppInstallation(context: Context) {
        val dpm = getDpm(context); val admin = getAdmin(context)
        if (isDeviceOwner(context)) {
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        }
    }

    // ─── Mobile hotspot / tethering ───────────────────────────────────────────
    // Standard MDM approach: DISALLOW_CONFIG_TETHERING stops the user turning on
    // or configuring the Wi-Fi/USB/Bluetooth hotspot. (Device Owner required.)

    fun disableHotspot(context: Context) {
        if (!isDeviceOwner(context)) return
        getDpm(context).addUserRestriction(getAdmin(context), UserManager.DISALLOW_CONFIG_TETHERING)
    }

    fun enableHotspot(context: Context) {
        if (!isDeviceOwner(context)) return
        getDpm(context).clearUserRestriction(getAdmin(context), UserManager.DISALLOW_CONFIG_TETHERING)
    }

    fun isHotspotDisabled(context: Context) =
        hasRestriction(context, UserManager.DISALLOW_CONFIG_TETHERING)

    // ─── Failed-unlock wipe protection ────────────────────────────────────────
    // Fix for "device factory-resets after N wrong PIN entries": push the
    // DevicePolicy failed-attempt threshold impossibly high so it is never hit.
    //
    // CAVEAT: some OEM builds (incl. certain Kyocera firmware) enforce their own
    // wipe-after-N below the DevicePolicy layer; that cannot be changed by any
    // app. On those devices the reliable fix is removing the lock credential
    // entirely (see disableScreenLock) so there is no failed attempt to count.

    fun setWipeGuard(context: Context, enabled: Boolean) {
        val dpm = getDpm(context); val admin = getAdmin(context)
        try {
            dpm.setMaximumFailedPasswordsForWipe(admin, if (enabled) WIPE_GUARD_ATTEMPTS else 0)
            Log.d(TAG, "maxFailedPasswordsForWipe = ${if (enabled) WIPE_GUARD_ATTEMPTS else 0}")
        } catch (e: Exception) {
            Log.e(TAG, "setMaximumFailedPasswordsForWipe failed: ${e.message}")
        }
    }

    fun isWipeGuardEnabled(context: Context): Boolean = try {
        getDpm(context).getMaximumFailedPasswordsForWipe(getAdmin(context)) >= WIPE_GUARD_ATTEMPTS
    } catch (e: Exception) { false }

    fun lockDevice(context: Context) = getDpm(context).lockNow()

    // ─── Always-on VPN (whitelist firewall) ───────────────────────────────────
    // As Device Owner we can mark our own VpnService always-on. This both
    // auto-grants the VPN consent (no user prompt) and auto-restarts the filter
    // after reboot or if it's killed. Per-app bypass is still handled inside the
    // VpnService (addDisallowedApplication), NOT via lockdown — lockdown would
    // also cut off the bypassed apps, which we don't want.
    fun setAlwaysOnVpn(context: Context, enabled: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        if (!isDeviceOwner(context)) return false
        return try {
            getDpm(context).setAlwaysOnVpnPackage(
                getAdmin(context),
                if (enabled) context.packageName else null,
                false
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "setAlwaysOnVpnPackage failed: ${e.message}")
            false
        }
    }

    // ─── Security toggles ─────────────────────────────────────────────────────

    fun disableDeveloperOptions(context: Context) {
        if (isDeviceOwner(context))
            getDpm(context).addUserRestriction(getAdmin(context), UserManager.DISALLOW_DEBUGGING_FEATURES)
    }

    fun enableDeveloperOptions(context: Context) {
        if (isDeviceOwner(context))
            getDpm(context).clearUserRestriction(getAdmin(context), UserManager.DISALLOW_DEBUGGING_FEATURES)
    }

    fun disableSafeBoot(context: Context) {
        if (isDeviceOwner(context))
            getDpm(context).addUserRestriction(getAdmin(context), UserManager.DISALLOW_SAFE_BOOT)
    }

    fun enableSafeBoot(context: Context) {
        if (isDeviceOwner(context))
            getDpm(context).clearUserRestriction(getAdmin(context), UserManager.DISALLOW_SAFE_BOOT)
    }

    fun disableCamera(context: Context) =
        getDpm(context).setCameraDisabled(getAdmin(context), true)

    fun enableCamera(context: Context) =
        getDpm(context).setCameraDisabled(getAdmin(context), false)

    fun disableScreenCapture(context: Context) =
        getDpm(context).setScreenCaptureDisabled(getAdmin(context), true)

    fun enableScreenCapture(context: Context) =
        getDpm(context).setScreenCaptureDisabled(getAdmin(context), false)

    fun disableFactoryReset(context: Context) {
        if (isDeviceOwner(context))
            getDpm(context).addUserRestriction(getAdmin(context), UserManager.DISALLOW_FACTORY_RESET)
    }

    fun enableFactoryReset(context: Context) {
        if (isDeviceOwner(context))
            getDpm(context).clearUserRestriction(getAdmin(context), UserManager.DISALLOW_FACTORY_RESET)
    }

    // ── State getters (so UI switches reflect what's actually saved) ──────
    fun isCameraDisabled(context: Context): Boolean = try {
        getDpm(context).getCameraDisabled(getAdmin(context))
    } catch (e: Exception) { false }

    fun isScreenCaptureDisabled(context: Context): Boolean = try {
        getDpm(context).getScreenCaptureDisabled(getAdmin(context))
    } catch (e: Exception) { false }

    private fun hasRestriction(context: Context, key: String): Boolean = try {
        getDpm(context).getUserRestrictions(getAdmin(context)).getBoolean(key, false)
    } catch (e: Exception) { false }

    fun isSafeBootDisabled(context: Context) =
        hasRestriction(context, UserManager.DISALLOW_SAFE_BOOT)
    fun isDeveloperOptionsDisabled(context: Context) =
        hasRestriction(context, UserManager.DISALLOW_DEBUGGING_FEATURES)
    fun isFactoryResetDisabled(context: Context) =
        hasRestriction(context, UserManager.DISALLOW_FACTORY_RESET)
    fun isAppInstallBlocked(context: Context) =
        hasRestriction(context, UserManager.DISALLOW_INSTALL_APPS)
    fun isUnknownSourcesBlocked(context: Context) =
        hasRestriction(context, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)

    // ── Screen lock control ──────────────────────────────────────────────
    // The real goal on OEM builds (e.g. Kyocera) that wipe after N failed
    // lockscreen attempts: remove the lockscreen credential entirely, and stop
    // anything from requiring a new one. No credential -> no failed attempts
    // -> no firmware wipe.

    fun disableScreenLock(context: Context): Boolean {
        val dpm = getDpm(context); val admin = getAdmin(context)
        try {
            dpm.setPasswordQuality(admin, DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED)
            dpm.setPasswordMinimumLength(admin, 0)
            dpm.setPasswordMinimumLetters(admin, 0)
            dpm.setPasswordMinimumNumeric(admin, 0)
            dpm.setPasswordMinimumSymbols(admin, 0)
            dpm.setPasswordMinimumUpperCase(admin, 0)
            dpm.setPasswordMinimumLowerCase(admin, 0)
            dpm.setPasswordMinimumNonLetter(admin, 0)
        } catch (e: Exception) {
            Log.e(TAG, "clearing password quality failed: ${e.message}")
        }

        clearCredentialRestriction(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { dpm.setKeyguardDisabled(admin, true) }
            catch (e: Exception) { Log.w(TAG, "setKeyguardDisabled failed: ${e.message}") }
        }

        return try {
            @Suppress("DEPRECATION")
            val ok = dpm.resetPassword("", 0)
            try {
                android.provider.Settings.Secure.putInt(
                    context.contentResolver, "lockscreen.disabled", 1)
            } catch (_: Exception) {}
            ok
        } catch (e: Exception) {
            Log.e(TAG, "resetPassword failed (expected on API 26+): ${e.message}")
            false
        }
    }

    fun enableScreenLock(context: Context) {
        val dpm = getDpm(context); val admin = getAdmin(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { dpm.setKeyguardDisabled(admin, false) } catch (_: Exception) {}
        }
        try {
            android.provider.Settings.Secure.putInt(
                context.contentResolver, "lockscreen.disabled", 0)
        } catch (_: Exception) {}
        clearCredentialRestriction(context)
    }

    /** Clears DISALLOW_CONFIG_CREDENTIALS (it blocks cert installs and never did
     *  the intended lockscreen job). */
    private fun clearCredentialRestriction(context: Context) {
        if (!isDeviceOwner(context)) return
        try {
            getDpm(context).clearUserRestriction(getAdmin(context),
                UserManager.DISALLOW_CONFIG_CREDENTIALS)
        } catch (e: Exception) {
            Log.e(TAG, "clearing DISALLOW_CONFIG_CREDENTIALS failed: ${e.message}")
        }
    }

    fun isScreenLockDisabled(context: Context): Boolean {
        return try {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            km.isDeviceSecure.not()
        } catch (e: Exception) {
            false
        }
    }
}
