package com.mdmagent.util

import android.content.Context
import androidx.core.content.edit

/**
 * Plain (non-encrypted) storage for device-policy preferences that are not
 * secrets: the VPN whitelist, per-app bypass list, and feature flags.
 *
 * Kept deliberately simple and crash-proof — these values drive enforcement,
 * so a flaky keystore (see PasswordManager) must never be able to wipe them.
 */
object PolicyPrefs {
    private const val FILE = "mdm_policy"

    private const val K_WL_ENABLED   = "whitelist_enabled"
    private const val K_WL_DOMAINS   = "whitelist_domains"
    private const val K_BYPASS_APPS  = "bypass_apps"
    private const val K_TOUCH_BLOCK  = "touch_block_enabled"
    private const val K_LOGGING      = "filter_logging_enabled"

    private fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ── Whitelist master switch ──────────────────────────────────────────
    fun isWhitelistEnabled(ctx: Context) = p(ctx).getBoolean(K_WL_ENABLED, false)
    fun setWhitelistEnabled(ctx: Context, on: Boolean) = p(ctx).edit { putBoolean(K_WL_ENABLED, on) }

    // ── Allowed domains ──────────────────────────────────────────────────
    fun getWhitelist(ctx: Context): Set<String> =
        p(ctx).getStringSet(K_WL_DOMAINS, emptySet())?.toSortedSet() ?: emptySet()

    fun addDomain(ctx: Context, domain: String) {
        val d = normalizeDomain(domain) ?: return
        p(ctx).edit { putStringSet(K_WL_DOMAINS, getWhitelist(ctx).toMutableSet().apply { add(d) }) }
    }

    fun removeDomain(ctx: Context, domain: String) {
        p(ctx).edit {
            putStringSet(K_WL_DOMAINS, getWhitelist(ctx).toMutableSet().apply { remove(domain) })
        }
    }

    /** Strip scheme/path/port and lowercase; returns null if nothing usable. */
    fun normalizeDomain(raw: String): String? {
        var s = raw.trim().lowercase()
        if (s.isEmpty()) return null
        s = s.removePrefix("https://").removePrefix("http://")
        s = s.substringBefore('/')
        s = s.substringBefore(':')
        s = s.removePrefix("*.")                 // we already match subdomains
        return s.ifEmpty { null }
    }

    // ── Per-app bypass (packages routed AROUND the whitelist VPN) ─────────
    fun getBypassApps(ctx: Context): Set<String> =
        p(ctx).getStringSet(K_BYPASS_APPS, emptySet()) ?: emptySet()

    fun isBypassed(ctx: Context, pkg: String) = getBypassApps(ctx).contains(pkg)

    fun setBypassed(ctx: Context, pkg: String, bypass: Boolean) {
        p(ctx).edit {
            putStringSet(K_BYPASS_APPS, getBypassApps(ctx).toMutableSet().apply {
                if (bypass) add(pkg) else remove(pkg)
            })
        }
    }

    // ── Touchscreen block ────────────────────────────────────────────────
    fun isTouchBlockEnabled(ctx: Context) = p(ctx).getBoolean(K_TOUCH_BLOCK, false)
    fun setTouchBlockEnabled(ctx: Context, on: Boolean) = p(ctx).edit { putBoolean(K_TOUCH_BLOCK, on) }

    // ── Connection logging ───────────────────────────────────────────────
    fun isLoggingEnabled(ctx: Context) = p(ctx).getBoolean(K_LOGGING, true)
    fun setLoggingEnabled(ctx: Context, on: Boolean) = p(ctx).edit { putBoolean(K_LOGGING, on) }
}
