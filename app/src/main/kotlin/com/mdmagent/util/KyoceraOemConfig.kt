package com.mdmagent.util

import android.content.Context
import android.content.RestrictionsManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log

/**
 * Drives Kyocera's OEMConfig plugin ("Kyocera Device Config") via managed
 * app restrictions. As Device Owner we can push these without an EMM vendor.
 *
 * Key point: we DISCOVER the real restriction keys from the installed plugin
 * with RestrictionsManager.getManifestRestrictions() rather than hardcoding a
 * guessed name — a wrong key silently no-ops, which for "disable recovery" is
 * exactly the kind of silent failure we must avoid.
 *
 * The plugin package name has shipped under a couple of ids across Kyocera
 * builds; we probe the known candidates and use whichever is installed.
 */
object KyoceraOemConfig {

    private const val TAG = "KyoceraOEM"

    // Known/likely package ids for the Kyocera device-config plugin.
    // We resolve whichever is actually installed at runtime.
    // The Kyocera OEMConfig plugin. The FIRST entry is the confirmed package
    // on the DuraXV E4810 (decompiled from DeviceConfigPlugin.apk); the rest
    // are fallbacks for other Kyocera builds.
    private val CANDIDATE_PACKAGES = listOf(
        "jp.kyocera.enterprisedeviceconfig",   // CONFIRMED — E4810
        "com.kyocera.deviceconfig",
        "com.kyocera.devicecontrol",
        "com.kyocera.oemconfig",
        "com.kyocera.kdc",
        "com.kyocera.enterprisedeviceconfig",
    )

    // Confirmed key from jp.kyocera.enterprisedeviceconfig res/xml/restrictions.xml
    private const val RECOVERY_KEY = "key_restriction_disallow_recovery_mode"

    /**
     * Curated boolean restrictions worth exposing as switches, from the
     * confirmed 1.1.03 schema that works on the E4810. label -> key.
     * (The plugin exposes ~40 keys; these are the ones useful for a locked
     * fleet device. The full raw list is still shown lower on the screen.)
     */
    val CURATED_TOGGLES: List<Pair<String, String>> = listOf(
        "Disable Recovery Mode"        to "key_restriction_disallow_recovery_mode",
        "Disable USB debugging (ADB)"  to "key_restriction_disallow_usb_debug",
        "Disable power-off by user"    to "key_restriction_disallow_power_off_by_user",
        "Disable OTA updates"          to "key_restriction_disallow_ota_updates",
        "Disable factory reset (SIM)"  to "key_restriction_disallow_simcard_lock",
        "Disable clipboard"            to "key_restriction_disallow_clipboard",
        "Disable mock location"        to "key_restriction_disallow_mock_location",
        "Disable Doze mode"            to "key_restriction_disallow_disallow_doze",
        "Disable USB host"             to "key_restriction_disallow_usb_host",
        "Disable USB tethering"        to "key_restriction_disallow_usb_tether",
        "Disable safe/quick settings edit" to "key_restriction_disallow_quicksetting_edit",
        "Disable default-launcher change"  to "key_restriction_disallow_default_launcher",
        "Force SD card encryption"     to "key_restriction_force_sdcard_encryption",
    )

    // Substrings we look for in a restriction key/title to auto-spot the
    // "disable recovery mode" control across naming variations.
    private val RECOVERY_HINTS = listOf("recovery")

    data class OemKey(
        val key: String,
        val type: String,
        val title: String?,
        val description: String?,
        val currentBool: Boolean?
    )

    /** The installed plugin package, or null if none of the candidates exist. */
    fun installedPluginPackage(ctx: Context): String? {
        val pm = ctx.packageManager
        for (pkg in CANDIDATE_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        return null
    }

    fun isPluginInstalled(ctx: Context): Boolean = installedPluginPackage(ctx) != null

    /**
     * Enumerate every restriction the installed plugin exposes, straight from
     * its manifest. This is what to display so you can see the exact key names
     * this specific device/firmware supports.
     */
    fun listRestrictions(ctx: Context): List<OemKey> {
        val pkg = installedPluginPackage(ctx) ?: return emptyList()
        val rm = ctx.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
        val existing = try {
            DeviceAdminHelper.getDpm(ctx)
                .getApplicationRestrictions(DeviceAdminHelper.getAdmin(ctx), pkg)
        } catch (e: Exception) {
            Bundle()
        }

        return try {
            rm.getManifestRestrictions(pkg).map { entry ->
                OemKey(
                    key = entry.key,
                    type = restrictionTypeName(entry.type),
                    title = entry.title,
                    description = entry.description,
                    currentBool = if (existing.containsKey(entry.key))
                        existing.getBoolean(entry.key) else null
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getManifestRestrictions failed: ${e.message}")
            emptyList()
        }
    }

    /** Best guess at the recovery-disable key for THIS plugin, or null. */
    fun findRecoveryKey(ctx: Context): OemKey? {
        val all = listRestrictions(ctx)
        // Prefer the exact confirmed key name.
        all.firstOrNull { it.key == RECOVERY_KEY }?.let { return it }
        // Fall back to fuzzy matching for other Kyocera builds.
        return all.firstOrNull { k ->
            val hay = (k.key + " " + (k.title ?: "") + " " + (k.description ?: ""))
                .lowercase()
            RECOVERY_HINTS.all { hint -> hay.contains(hint) } &&
                    (hay.contains("disable") || hay.contains("prevent") ||
                     hay.contains("block") || hay.contains("disallow") ||
                     k.type == "bool")
        } ?: all.firstOrNull { (it.key + " " + (it.title ?: "")).lowercase().contains("recovery") }
    }

    /**
     * Set a single boolean restriction on the plugin, preserving any others
     * already set (getApplicationRestrictions -> modify -> setApplicationRestrictions).
     */
    fun setBooleanRestriction(ctx: Context, key: String, value: Boolean): Boolean {
        val pkg = installedPluginPackage(ctx) ?: return false
        if (!DeviceAdminHelper.isDeviceOwner(ctx)) return false
        return try {
            val dpm = DeviceAdminHelper.getDpm(ctx)
            val admin = DeviceAdminHelper.getAdmin(ctx)
            val bundle = dpm.getApplicationRestrictions(admin, pkg) ?: Bundle()
            bundle.putBoolean(key, value)
            dpm.setApplicationRestrictions(admin, pkg, bundle)
            Log.d(TAG, "set $key=$value on $pkg")
            true
        } catch (e: Exception) {
            Log.e(TAG, "setApplicationRestrictions failed: ${e.message}")
            false
        }
    }

    /** Convenience: disable (or re-enable) recovery mode using the discovered key. */
    /** Current boolean value of one restriction key (false if unset/unknown). */
    fun getBooleanRestriction(ctx: Context, key: String): Boolean {
        val pkg = installedPluginPackage(ctx) ?: return false
        return try {
            DeviceAdminHelper.getDpm(ctx)
                .getApplicationRestrictions(DeviceAdminHelper.getAdmin(ctx), pkg)
                ?.getBoolean(key, false) ?: false
        } catch (e: Exception) { false }
    }

    fun setRecoveryDisabled(ctx: Context, disabled: Boolean): Pair<Boolean, String> {
        if (!isPluginInstalled(ctx))
            return false to "Kyocera OEMConfig plugin not installed"
        val rk = findRecoveryKey(ctx)
            ?: return false to "No recovery-mode key found in this plugin"
        val ok = setBooleanRestriction(ctx, rk.key, disabled)
        return ok to if (ok) "Set ${rk.key}=$disabled" else "Failed to set ${rk.key}"
    }

    /** Read current recovery-disabled state, if known. */
    fun isRecoveryDisabled(ctx: Context): Boolean {
        return findRecoveryKey(ctx)?.currentBool == true
    }

    private fun restrictionTypeName(type: Int): String = when (type) {
        android.content.RestrictionEntry.TYPE_BOOLEAN -> "bool"
        android.content.RestrictionEntry.TYPE_CHOICE -> "choice"
        android.content.RestrictionEntry.TYPE_MULTI_SELECT -> "multi"
        android.content.RestrictionEntry.TYPE_INTEGER -> "int"
        android.content.RestrictionEntry.TYPE_STRING -> "string"
        android.content.RestrictionEntry.TYPE_BUNDLE -> "bundle"
        android.content.RestrictionEntry.TYPE_BUNDLE_ARRAY -> "bundle[]"
        else -> "type$type"
    }
}
