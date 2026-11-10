package com.mdmagent.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mdmagent.databinding.ActivityProvisioningCompleteBinding
import com.mdmagent.util.PasswordManager

/**
 * Shown immediately after QR provisioning completes.
 * Device Owner is already active at this point.
 * Forces the admin to set their password before proceeding.
 */
class ProvisioningCompleteActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProvisioningCompleteBinding
    private var firstEntry = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProvisioningCompleteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Password hasn't been set yet at this point
        showStep1()

        binding.btnNext.setOnClickListener {
            val input = binding.etPassword.text.toString()
            if (firstEntry.isEmpty()) {
                handleStep1(input)
            } else {
                handleStep2(input)
            }
        }
    }

    private fun showStep1() {
        binding.tvStep.text = "Step 1 of 2"
        binding.tvInstruction.text = "Create your administrator password.\nYou will need this to open the app and to unenroll this device."
        binding.etPassword.hint = "Enter new password"
        binding.etPassword.setText("")
        binding.btnNext.text = "Next →"
    }

    private fun showStep2() {
        binding.tvStep.text = "Step 2 of 2"
        binding.tvInstruction.text = "Confirm your administrator password."
        binding.etPassword.hint = "Confirm password"
        binding.etPassword.setText("")
        binding.btnNext.text = "Activate ✓"
    }

    private fun handleStep1(input: String) {
        if (input.length < 4) {
            binding.etPassword.error = "Minimum 4 characters"
            return
        }
        firstEntry = input
        showStep2()
    }

    private fun handleStep2(input: String) {
        if (input != firstEntry) {
            firstEntry = ""
            binding.etPassword.error = "Passwords don't match"
            showStep1()
            Toast.makeText(this, "Passwords don't match — start over", Toast.LENGTH_SHORT).show()
            return
        }
        PasswordManager.setupPassword(this, input)
        Toast.makeText(this, "✅ Device Manager is active!", Toast.LENGTH_LONG).show()

        // Go straight to the dashboard — password is now set
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    // Cannot back out of provisioning setup
    override fun onBackPressed() { }
}
