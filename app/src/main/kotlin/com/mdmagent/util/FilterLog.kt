package com.mdmagent.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny rolling log of whitelist decisions (ALLOW / BLOCK <domain>), shown in the
 * Whitelist screen. Append-only to a capped file; never grows without bound.
 */
object FilterLog {
    private const val FILE = "vpn_filter.log"
    private const val MAX_BYTES = 128 * 1024
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun record(ctx: Context, allowed: Boolean, domain: String) {
        if (!PolicyPrefs.isLoggingEnabled(ctx)) return
        try {
            val f = File(ctx.filesDir, FILE)
            if (f.exists() && f.length() > MAX_BYTES) trim(f)
            val tag = if (allowed) "ALLOW" else "BLOCK"
            f.appendText("${fmt.format(Date())}  $tag  $domain\n")
        } catch (_: Exception) {}
    }

    fun read(ctx: Context): String = try {
        val f = File(ctx.filesDir, FILE)
        if (f.exists()) f.readText().lineSequence()
            .filter { it.isNotBlank() }.toList().takeLast(400).reversed().joinToString("\n")
        else ""
    } catch (_: Exception) { "" }

    fun clear(ctx: Context) {
        try { File(ctx.filesDir, FILE).delete() } catch (_: Exception) {}
    }

    private fun trim(f: File) {
        try {
            val keep = f.readLines().takeLast(1000)
            f.writeText(keep.joinToString("\n") + "\n")
        } catch (_: Exception) { f.delete() }
    }
}
