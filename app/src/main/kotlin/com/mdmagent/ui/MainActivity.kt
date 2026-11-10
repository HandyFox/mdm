package com.mdmagent.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mdmagent.R
import com.mdmagent.databinding.ActivityMainBinding
import com.mdmagent.receiver.MdmAdminReceiver
import com.mdmagent.util.DeviceAdminHelper
import com.mdmagent.util.PolicyPrefs

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MdmAdminReceiver::class.java)

        binding.cardApps.setOnClickListener { open(AppManagerActivity::class.java) }
        binding.cardWhitelist.setOnClickListener { open(WhitelistActivity::class.java) }
        binding.cardSecurity.setOnClickListener { open(SecurityActivity::class.java) }
        binding.cardOem.setOnClickListener { open(OemConfigActivity::class.java) }
        binding.cardLock.setOnClickListener { confirmLock() }
        binding.btnActivateAdmin.setOnClickListener { requestDeviceAdmin() }

        updateStatus()
        binding.cardApps.requestFocus()   // land focus for D-pad devices
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun open(cls: Class<*>) = startActivity(Intent(this, cls))

    private fun confirmLock() {
        AlertDialog.Builder(this)
            .setTitle("Lock device")
            .setMessage("Lock the screen now?")
            .setPositiveButton("Lock") { _, _ -> DeviceAdminHelper.lockDevice(this) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateStatus() {
        val isAdmin = DeviceAdminHelper.isAdminActive(this)
        val isOwner = DeviceAdminHelper.isDeviceOwner(this)

        binding.tvAdminStatus.text = when {
            isOwner -> "Device Owner — full control"
            isAdmin -> "Device Administrator — active"
            else    -> "Not activated — tap to activate"
        }
        binding.tvAdminStatus.setTextColor(ContextCompat.getColor(this,
            if (isOwner) R.color.success else if (isAdmin) R.color.primary else R.color.warn))
        binding.btnActivateAdmin.visibility = if (isAdmin) View.GONE else View.VISIBLE

        val wl = PolicyPrefs.isWhitelistEnabled(this)
        val touch = PolicyPrefs.isTouchBlockEnabled(this)
        binding.tvFilterStatus.text = buildString {
            append("Web filter: ").append(if (wl) "On (${PolicyPrefs.getWhitelist(this@MainActivity).size} domains)" else "Off")
            if (touch) append("   •   Touchscreen: disabled")
        }
    }

    private fun requestDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Device Manager needs administrator access to enforce security policies.")
        }
        startActivityForResult(intent, 1001)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            if (DeviceAdminHelper.isAdminActive(this))
                Toast.makeText(this, "Device Administrator activated", Toast.LENGTH_LONG).show()
            updateStatus()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_change_password -> { open(ChangePasswordActivity::class.java); true }
        R.id.action_unenroll -> { open(UnenrollActivity::class.java); true }
        R.id.action_about -> {
            AlertDialog.Builder(this)
                .setTitle("About Device Manager")
                .setMessage("This device is managed by Device Manager.\n\n" +
                    "Security policies are enforced to protect it. Contact your " +
                    "administrator for assistance.")
                .setPositiveButton("OK", null)
                .show()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
