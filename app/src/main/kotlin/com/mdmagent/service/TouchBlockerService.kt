package com.mdmagent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.mdmagent.R
import com.mdmagent.util.PolicyPrefs

/**
 * Disables the touchscreen in software by laying a transparent, full-screen
 * overlay over everything and swallowing every touch that lands on it.
 *
 * Hardware keys / D-pad / keypad still work, because the overlay window is
 * FLAG_NOT_FOCUSABLE — it never receives key events, so those fall through to
 * the app underneath. This is how a device like the LG Wine can be made
 * keypad-only without root.
 */
class TouchBlockerService : android.app.Service() {

    private var overlay: View? = null
    private var wm: WindowManager? = null

    companion object {
        private const val TAG = "TouchBlocker"
        private const val CHANNEL_ID = "mdm_touch"
        private const val NOTIF_ID = 1003

        fun start(ctx: Context) {
            ctx.startService(Intent(ctx, TouchBlockerService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TouchBlockerService::class.java))
        }

        /** Overlay windows need "display over other apps". */
        fun hasOverlayPermission(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafe()
        addOverlay()
        return START_STICKY
    }

    private fun addOverlay() {
        if (overlay != null) return
        if (!hasOverlayPermission(this)) {
            Log.w(TAG, "no overlay permission; cannot block touch")
            stopSelf(); return
        }
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // NOT_FOCUSABLE => keys fall through to the app (keypad still works).
            // We intentionally do NOT set NOT_TOUCHABLE: the window swallows touch.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val v = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            // Consume every touch so it never reaches the UI below.
            setOnTouchListener { _, _ -> true }
            isClickable = true
            isLongClickable = true
        }

        try {
            wm?.addView(v, params)
            overlay = v
            Log.d(TAG, "touch overlay added")
        } catch (e: Exception) {
            Log.e(TAG, "addView failed: ${e.message}")
            stopSelf()
        }
    }

    private fun removeOverlay() {
        try { overlay?.let { wm?.removeView(it) } } catch (_: Exception) {}
        overlay = null
    }

    override fun onDestroy() {
        removeOverlay()
        PolicyPrefs.setTouchBlockEnabled(this, false)
        super.onDestroy()
    }

    private fun startForegroundSafe() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "Touchscreen Lock", NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) })

        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Touchscreen disabled")
            .setContentText("Use the keypad to navigate")
            .setSmallIcon(R.drawable.ic_touch_off)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            } else startForeground(NOTIF_ID, notif)
        } catch (e: Exception) {
            try { startForeground(NOTIF_ID, notif) } catch (_: Exception) {}
        }
    }
}
