package com.mdmagent.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.UserManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.mdmagent.databinding.ActivityUnenrollBinding
import com.mdmagent.receiver.MdmAdminReceiver
import com.mdmagent.util.PasswordManager

/**
 * The ONLY way to remove Device Owner from this device.
 * Requires the administrator password. Once confirmed, all
 * restrictions are cleared and Device Owner status is removed.
 *
 * Because we applied DISALLOW_FACTORY_RESET and blocked ADB,
 * this is the only legitimate removal path available to the user.
 */
class UnenrollActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUnenrollBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUnenrollBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Unenroll Device"

        binding.btnUnenroll.setOnClickListener {
            val password = binding.etPassword.text.toString()
            if (password.isEmpty()) {
                binding.etPassword.error = "Enter administrator password"
                return@setOnClickListener
            }
            if (!PasswordManager.verifyPassword(this, password)) {
                binding.etPassword.error = "Incorrect password"
                binding.etPassword.setText("")
                return@setOnClickListener
            }
            // Password correct — confirm with a dialog before proceeding
            showConfirmDialog()
        }
    }

    private fun showConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Confirm Unenrollment")
            .setMessage(
                "This will:\n\n" +
                "• Remove Device Owner status\n" +
                "• Remove all security policy restrictions\n" +
                "• Re-enable app installation, ADB, and developer options\n" +
                "• Allow factory reset\n\n" +
                "This action cannot be undone. Are you sure?"
            )
            .setPositiveButton("Yes, Unenroll") { _, _ -> performUnenroll() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performUnenroll() {
        try {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, MdmAdminReceiver::class.java)

            // Clear all restrictions before removing Device Owner
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)

            // Remove Device Owner — this also removes admin rights
            dpm.clearDeviceOwnerApp(packageName)

            Toast.makeText(this, "Device Manager removed successfully", Toast.LENGTH_LONG).show()

            // Close the app
            finishAffinity()
        } catch (e: Exception) {
            Toast.makeText(this, "Error during unenrollment: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
