package com.mdmagent.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mdmagent.R
import com.mdmagent.databinding.ActivityPasswordGateBinding
import com.mdmagent.util.PasswordManager

/**
 * First screen shown on every app launch.
 * - If no password is set → show Setup flow (enter + confirm)
 * - If password is set → show Login flow
 * Both flows lead to MainActivity on success.
 */
class PasswordGateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPasswordGateBinding
    private var isSetupMode = false
    private var setupFirstEntry = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPasswordGateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isSetupMode = !PasswordManager.isPasswordSetup(this)

        if (isSetupMode) {
            showSetupMode()
        } else {
            showLoginMode()
        }

        binding.btnAction.setOnClickListener {
            val input = binding.etPassword.text.toString()
            if (isSetupMode) handleSetup(input) else handleLogin(input)
        }
    }

    private fun showSetupMode() {
        binding.tvTitle.text = "Set Administrator Password"
        binding.tvSubtitle.text = "Create a password to protect this app.\nYou will need this every time you open it."
        binding.tvStep.text = "Step 1 of 2 — Enter new password"
        binding.btnAction.text = "Set Password"
        binding.tvHint.text = "Minimum 4 characters"
        binding.tvHint.visibility = View.VISIBLE
    }

    private fun showSetupConfirmMode() {
        binding.tvTitle.text = "Confirm Password"
        binding.tvSubtitle.text = "Re-enter your administrator password to confirm."
        binding.tvStep.text = "Step 2 of 2 — Confirm password"
        binding.btnAction.text = "Confirm & Activate"
        binding.etPassword.setText("")
        binding.tvHint.visibility = View.GONE
    }

    private fun showLoginMode() {
        binding.tvTitle.text = "Administrator Login"
        binding.tvSubtitle.text = "This app is password protected.\nEnter your administrator password to continue."
        binding.tvStep.text = "Enter your password"
        binding.btnAction.text = "Unlock"
        binding.tvHint.visibility = View.GONE
    }

    private fun handleSetup(input: String) {
        if (setupFirstEntry.isEmpty()) {
            // Step 1: collect first entry
            if (input.length < 4) {
                shakeAndClear("Password must be at least 4 characters")
                return
            }
            setupFirstEntry = input
            showSetupConfirmMode()
        } else {
            // Step 2: confirm
            if (input != setupFirstEntry) {
                setupFirstEntry = ""
                shakeAndClear("Passwords don't match. Start over.")
                showSetupMode()
                return
            }
            val success = PasswordManager.setupPassword(this, input)
            if (success) {
                Toast.makeText(this, "✅ Administrator password set!", Toast.LENGTH_SHORT).show()
                goToMain()
            } else {
                shakeAndClear("Failed to save password. Try again.")
            }
        }
    }

    private fun handleLogin(input: String) {
        if (PasswordManager.verifyPassword(this, input)) {
            goToMain()
        } else {
            shakeAndClear("Incorrect password")
        }
    }

    private fun shakeAndClear(message: String) {
        val shake = AnimationUtils.loadAnimation(this, R.anim.shake)
        binding.etPassword.startAnimation(shake)
        binding.etPassword.setText("")
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // Block back button — don't allow bypassing the password screen
    override fun onBackPressed() {
        // Do nothing — user cannot back out of password screen
    }
}
