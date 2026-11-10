package com.mdmagent.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mdmagent.R
import com.mdmagent.databinding.ActivityWhitelistBinding
import com.mdmagent.service.MdmVpnService
import com.mdmagent.util.DeviceAdminHelper
import com.mdmagent.util.FilterLog
import com.mdmagent.util.PolicyPrefs

/**
 * The VPN-based web whitelist. When enabled, all web traffic is blocked except
 * the listed domains (and their subdomains). Apps can be excluded from the
 * filter via the App Bypass picker.
 */
class WhitelistActivity : AppCompatActivity() {

    private lateinit var b: ActivityWhitelistBinding
    private val REQ_VPN = 2001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityWhitelistBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        b.rowEnable.bind(PolicyPrefs.isWhitelistEnabled(this)) { on ->
            if (on) enableFilter() else disableFilter()
        }

        b.btnAddDomain.setOnClickListener { addDomain() }
        b.etDomain.setOnEditorActionListener { _, _, _ -> addDomain(); true }

        b.rowBypassApps.setOnClickListener {
            startActivity(Intent(this, BypassAppsActivity::class.java))
        }

        b.rowLogging.bind(PolicyPrefs.isLoggingEnabled(this)) { on ->
            PolicyPrefs.setLoggingEnabled(this, on)
        }
        b.btnRefreshLog.setOnClickListener { refreshLog() }
        b.btnClearLog.setOnClickListener { FilterLog.clear(this); refreshLog() }

        renderDomains()
        refreshLog()
        b.rowEnable.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        val n = PolicyPrefs.getBypassApps(this).size
        b.tvBypassCount.text = if (n == 0) "No apps selected" else "$n app${if (n == 1) "" else "s"} selected"
        b.rowEnable.isChecked = PolicyPrefs.isWhitelistEnabled(this)
    }

    // ── enable / disable ────────────────────────────────────────────────────
    private fun enableFilter() {
        PolicyPrefs.setWhitelistEnabled(this, true)
        // Device Owner: always-on VPN auto-grants consent and auto-restarts.
        if (DeviceAdminHelper.setAlwaysOnVpn(this, true)) {
            // Lock it so the user can't disconnect it from Settings.
            DeviceAdminHelper.setVpnConfigLocked(this, true)
            MdmVpnService.start(this)
            toast(if (DeviceAdminHelper.isVpnConfigLocked(this))
                "Web filter enabled & locked" else "Web filter enabled")
            return
        }
        // Otherwise request the one-time VPN consent.
        val prep = VpnService.prepare(this)
        if (prep != null) startActivityForResult(prep, REQ_VPN)
        else { MdmVpnService.start(this); toast("Web filter enabled") }
    }

    private fun disableFilter() {
        PolicyPrefs.setWhitelistEnabled(this, false)
        // Unlock first (admin API still works while locked), then tear down.
        DeviceAdminHelper.setVpnConfigLocked(this, false)
        DeviceAdminHelper.setAlwaysOnVpn(this, false)
        MdmVpnService.stop(this)
        toast("Web filter disabled")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) { MdmVpnService.start(this); toast("Web filter enabled") }
            else {
                PolicyPrefs.setWhitelistEnabled(this, false)
                b.rowEnable.isChecked = false
                toast("VPN permission denied — filter not enabled")
            }
        }
    }

    // ── domains ─────────────────────────────────────────────────────────────
    private fun addDomain() {
        val norm = PolicyPrefs.normalizeDomain(b.etDomain.text.toString())
        if (norm == null) { toast("Enter a valid domain"); return }
        PolicyPrefs.addDomain(this, norm)
        b.etDomain.setText("")
        renderDomains()
        MdmVpnService.reconfigure(this)
        toast("Added $norm")
    }

    private fun confirmRemove(domain: String) {
        AlertDialog.Builder(this)
            .setTitle("Remove domain")
            .setMessage("Stop allowing “$domain”?")
            .setPositiveButton("Remove") { _, _ ->
                PolicyPrefs.removeDomain(this, domain)
                renderDomains()
                MdmVpnService.reconfigure(this)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderDomains() {
        val list = PolicyPrefs.getWhitelist(this).toList().sorted()
        b.containerDomains.removeAllViews()
        b.tvDomainsEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        for (d in list) b.containerDomains.addView(domainRow(d))
    }

    private fun domainRow(domain: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_setting_row)
            isFocusable = true; isFocusableInTouchMode = false; isClickable = true
            minimumHeight = dp(52)
            setPadding(dp(16), dp(10), dp(12), dp(10))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            layoutParams = lp
            setOnClickListener { confirmRemove(domain) }
        }
        row.addView(TextView(this).apply {
            text = domain
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_logout)
            setColorFilter(ContextCompat.getColor(context, R.color.danger))
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            contentDescription = "Remove"
        })
        return row
    }

    private fun refreshLog() {
        val text = FilterLog.read(this)
        b.tvLog.text = if (text.isBlank()) "No activity yet." else text
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
