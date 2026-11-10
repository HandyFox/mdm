package com.mdmagent.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.mdmagent.R
import com.mdmagent.databinding.ActivityAppManagerBinding
import com.mdmagent.receiver.MdmAdminReceiver
import com.mdmagent.util.DeviceAdminHelper
import kotlinx.coroutines.*

class AppManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppManagerBinding
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "App Manager"

        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MdmAdminReceiver::class.java)

        binding.rvApps.layoutManager = LinearLayoutManager(this)
        loadApps()
    }

    private fun loadApps() {
        binding.progressBar.visibility = View.VISIBLE
        scope.launch {
            try {
                val apps = withContext(Dispatchers.IO) { getInstalledApps() }
                binding.progressBar.visibility = View.GONE
                
                if (apps.isEmpty()) {
                    Toast.makeText(this@AppManagerActivity, "No apps found. Check permissions.", Toast.LENGTH_LONG).show()
                } else {
                    binding.rvApps.adapter = AppAdapter(apps)
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@AppManagerActivity, "Error loading apps: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getInstalledApps(): List<AppInfo> {
        val pm = packageManager
        
        // Use MATCH_DISABLED_COMPONENTS and MATCH_UNINSTALLED_PACKAGES to see all apps
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES
        
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flags)
        }
        
        return packages
            .filter { it.packageName != packageName && it.applicationInfo != null }
            .map { pkg ->
                val app = pkg.applicationInfo!!
                AppInfo(
                    packageName = pkg.packageName,
                    appName = pm.getApplicationLabel(app).toString(),
                    icon = pm.getApplicationIcon(app),
                    isEnabled = app.enabled,
                    isHidden = isAppHidden(pkg.packageName)
                )
            }
            .sortedBy { it.appName }
    }

    private fun isAppHidden(packageName: String): Boolean {
        return if (DeviceAdminHelper.isDeviceOwner(this)) {
            try {
                dpm.isApplicationHidden(adminComponent, packageName)
            } catch (e: Exception) { 
                false 
            }
        } else {
            try {
                val state = packageManager.getApplicationEnabledSetting(packageName)
                state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER || 
                state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun toggleApp(info: AppInfo, enable: Boolean) {
        if (DeviceAdminHelper.isDeviceOwner(this)) {
            try {
                // Device Owner: Use setApplicationHidden to both disable and hide the app
                dpm.setApplicationHidden(adminComponent, info.packageName, !enable)
                Toast.makeText(this,
                    "${info.appName} ${if (enable) "restored" else "hidden and disabled"}",
                    Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            try {
                // Device Admin: Use setApplicationEnabledSetting which also hides it from launcher
                val state = if (enable)
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                else
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                
                packageManager.setApplicationEnabledSetting(info.packageName, state, PackageManager.DONT_KILL_APP)
                
                Toast.makeText(this,
                    "${info.appName} ${if (enable) "enabled" else "disabled and hidden"}",
                    Toast.LENGTH_SHORT).show()
            } catch (e: SecurityException) {
                Toast.makeText(this,
                    "⚠️ Permission denied. Full hiding requires Device Owner mode.",
                    Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    inner class AppAdapter(private val apps: List<AppInfo>) :
        RecyclerView.Adapter<AppAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView      = view.findViewById(R.id.iv_app_icon)
            val name: TextView       = view.findViewById(R.id.tv_app_name)
            val pkg: TextView        = view.findViewById(R.id.tv_package_name)
            val toggle: MaterialSwitch = view.findViewById(R.id.sw_app_toggle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false)
            return ViewHolder(view)
        }

        override fun getItemCount() = apps.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.icon.setImageDrawable(app.icon)
            holder.name.text = app.appName
            holder.pkg.text  = app.packageName

            // The app is considered "on" if it is enabled AND not hidden.
            holder.toggle.isChecked = app.isEnabled && !app.isHidden

            // The whole row is the hit + focus target (touch and D-pad alike);
            // the switch itself is not independently clickable.
            holder.itemView.setOnClickListener {
                val now = !holder.toggle.isChecked
                holder.toggle.isChecked = now
                toggleApp(app, now)
            }
        }
    }

    data class AppInfo(
        val packageName: String,
        val appName: String,
        val icon: android.graphics.drawable.Drawable,
        val isEnabled: Boolean,
        val isHidden: Boolean
    )

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
