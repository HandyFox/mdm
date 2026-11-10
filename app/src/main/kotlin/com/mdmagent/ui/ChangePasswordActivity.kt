package com.mdmagent.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mdmagent.databinding.ActivityChangePasswordBinding
import com.mdmagent.util.PasswordManager

class ChangePasswordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChangePasswordBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChangePasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Change Administrator Password"

        binding.btnChangePassword.setOnClickListener {
            val current = binding.etCurrentPassword.text.toString()
            val newPass  = binding.etNewPassword.text.toString()
            val confirm  = binding.etConfirmPassword.text.toString()

            when {
                current.isEmpty() -> {
                    binding.etCurrentPassword.error = "Enter current password"
                }
                newPass.length < 4 -> {
                    binding.etNewPassword.error = "Password must be at least 4 characters"
                }
                newPass != confirm -> {
                    binding.etConfirmPassword.error = "Passwords don't match"
                }
                else -> {
                    val success = PasswordManager.changePassword(this, current, newPass)
                    if (success) {
                        Toast.makeText(this, "✅ Password changed successfully", Toast.LENGTH_LONG).show()
                        finish()
                    } else {
                        binding.etCurrentPassword.error = "Current password is incorrect"
                        binding.etCurrentPassword.setText("")
                    }
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
