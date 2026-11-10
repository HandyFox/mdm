package com.mdmagent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mdmagent.R
import com.mdmagent.util.PolicyPrefs
import com.mdmagent.vpn.VpnEngine
import com.mdmagent.vpn.WhitelistEngine

/**
 * The whitelist firewall. When the whitelist is ON, this VpnService captures
 * all traffic and hands it to [VpnEngine], which forwards only whitelisted
 * domains and drops the rest. Apps in the per-app bypass list are excluded from
 * the tunnel entirely (addDisallowedApplication) so they keep unrestricted
 * network access.
 */
class MdmVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var engine: VpnEngine? = null

    companion object {
        private const val TAG = "MdmVpn"
        private const val CHANNEL_ID = "mdm_vpn"
        private const val NOTIF_ID = 1002
        const val ACTION_START = "com.mdmagent.vpn.START"
        const val ACTION_STOP = "com.mdmagent.vpn.STOP"
        const val ACTION_RECONFIGURE = "com.mdmagent.vpn.RECONFIGURE"

        fun start(ctx: Context) = ctx.startService(
            Intent(ctx, MdmVpnService::class.java).setAction(ACTION_START))

        fun stop(ctx: Context) = ctx.startService(
            Intent(ctx, MdmVpnService::class.java).setAction(ACTION_STOP))

        /** Re-apply config (whitelist / bypass-app changes) if running. */
        fun reconfigure(ctx: Context) {
            if (PolicyPrefs.isWhitelistEnabled(ctx))
                ctx.startService(Intent(ctx, MdmVpnService::class.java).setAction(ACTION_RECONFIGURE))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { teardown(); stopSelf(); return START_NOT_STICKY }
            else -> establishTunnel()
        }
        return START_STICKY
    }

    private fun establishTunnel() {
        teardown()
        if (!PolicyPrefs.isWhitelistEnabled(this)) { stopSelf(); return }

        startForegroundSafe()

        val builder = Builder()
            .setSession("MDM Web Filter")
            .setMtu(1500)
            .addAddress("10.111.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")

        // Capture (and drop) IPv6 too, so it can't slip around the filter.
        try {
            builder.addAddress("fd00:6d:6d::2", 128)
            builder.addRoute("::", 0)
        } catch (e: Exception) { Log.d(TAG, "ipv6 route skipped: ${e.message}") }

        // Never route our own traffic through ourselves.
        try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

        // Per-app bypass: these apps get around the whitelist entirely.
        for (pkg in PolicyPrefs.getBypassApps(this)) {
            try { builder.addDisallowedApplication(pkg) }
            catch (e: Exception) { Log.d(TAG, "bypass pkg missing: $pkg") }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            builder.setBlocking(true)
        }

        val pfd = try { builder.establish() } catch (e: Exception) {
            Log.e(TAG, "establish failed: ${e.message}"); null
        }
        if (pfd == null) { stopSelf(); return }
        tun = pfd

        val whitelist = WhitelistEngine(PolicyPrefs.getWhitelist(this))
        engine = VpnEngine(this, pfd, whitelist).also { it.start() }
        Log.d(TAG, "tunnel up (${PolicyPrefs.getWhitelist(this).size} domains, " +
                "${PolicyPrefs.getBypassApps(this).size} bypass apps)")
    }

    private fun teardown() {
        try { engine?.stop() } catch (_: Exception) {}
        engine = null
        try { tun?.close() } catch (_: Exception) {}
        tun = null
    }

    override fun onDestroy() { teardown(); super.onDestroy() }
    override fun onRevoke() { teardown(); stopSelf(); super.onRevoke() }

    private fun startForegroundSafe() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "Web Filter", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Whitelist firewall is active"; setShowBadge(false) })

        val tap = PendingIntent.getActivity(
            this, 0, packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Web filter active")
            .setContentText("Only whitelisted sites are allowed")
            .setSmallIcon(R.drawable.ic_globe)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(tap)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground: ${e.message}")
            try { startForeground(NOTIF_ID, notif) } catch (_: Exception) {}
        }
    }
}
