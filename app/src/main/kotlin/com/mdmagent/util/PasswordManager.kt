package com.mdmagent.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

/**
 * Handles all admin password operations.
 * Password is stored as a SHA-256 hash in EncryptedSharedPreferences.
 */
object PasswordManager {

    private const val PREFS_FILE = "mdm_secure_prefs"
    private const val KEY_PASSWORD_HASH = "admin_password_hash"
    private const val KEY_IS_SETUP = "password_setup_complete"

    private fun getPrefs(context: Context): SharedPreferences {
        return try {
            buildEncryptedPrefs(context)
        } catch (e: Exception) {
            // Keystore/crypto can get into a bad state (corrupt master key,
            // failed key unwrap). Wipe the encrypted store + key and rebuild
            // rather than crashing the app on launch.
            try {
                context.deleteSharedPreferences(PREFS_FILE)
            } catch (_: Exception) {
            }
            try {
                val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
                ks.load(null)
                ks.deleteEntry("_androidx_security_master_key_")
            } catch (_: Exception) {
            }
            buildEncryptedPrefs(context)
        }
    }

    private fun buildEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Returns true if the admin password has been set up */
    fun isPasswordSetup(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_IS_SETUP, false)
    }

    /**
     * Sets the admin password for the first time.
     * Should only be called when isPasswordSetup() == false.
     */
    fun setupPassword(context: Context, password: String): Boolean {
        if (password.length < 4) return false
        val hash = hashPassword(password)
        getPrefs(context).edit()
            .putString(KEY_PASSWORD_HASH, hash)
            .putBoolean(KEY_IS_SETUP, true)
            .apply()
        return true
    }

    /** Verifies a password attempt against the stored hash */
    fun verifyPassword(context: Context, attempt: String): Boolean {
        val storedHash = getPrefs(context).getString(KEY_PASSWORD_HASH, null) ?: return false
        return hashPassword(attempt) == storedHash
    }

    /**
     * Changes the admin password. Requires the current password to be verified first.
     * Returns true on success, false if currentPassword is wrong.
     */
    fun changePassword(context: Context, currentPassword: String, newPassword: String): Boolean {
        if (!verifyPassword(context, currentPassword)) return false
        if (newPassword.length < 4) return false
        val hash = hashPassword(newPassword)
        getPrefs(context).edit()
            .putString(KEY_PASSWORD_HASH, hash)
            .apply()
        return true
    }

    /**
     * Sets the admin password WITHOUT requiring the current one.
     * Used when the MDM console pushes a password at enrollment, so the
     * operator can look it up on the dashboard instead of inventing one here.
     */
    fun forceSetPassword(context: Context, newPassword: String): Boolean {
        if (newPassword.length < 4) return false
        val hash = hashPassword(newPassword)
        getPrefs(context).edit()
            .putString(KEY_PASSWORD_HASH, hash)
            .putBoolean(KEY_IS_SETUP, true)
            .apply()
        return true
    }

    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(password.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
