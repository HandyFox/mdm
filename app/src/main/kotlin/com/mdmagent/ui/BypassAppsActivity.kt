package com.mdmagent.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.mdmagent.R
import com.mdmagent.databinding.ActivityBypassAppsBinding
import com.mdmagent.service.MdmVpnService
import com.mdmagent.util.PolicyPrefs
import kotlinx.coroutines.*

/**
 * Picks which apps bypass the whitelist VPN. A bypassed app is added to the
 * VpnService's disallowed list, so its traffic never enters the filter.
 */
class BypassAppsActivity : AppCompatActivity() {

    private lateinit var b: ActivityBypassAppsBinding
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBypassAppsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        b.rvApps.layoutManager = LinearLayoutManager(this)
        load()
    }

    private fun load() {
        b.progressBar.visibility = View.VISIBLE
        scope.launch {
            val apps = withContext(Dispatchers.IO) { launchableApps() }
            b.progressBar.visibility = View.GONE
            b.rvApps.adapter = Adapter(apps)
        }
    }

    private fun launchableApps(): List<AppRow> {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(main, 0)
        val seen = HashSet<String>()
        return resolved.mapNotNull { ri ->
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName || !seen.add(pkg)) return@mapNotNull null
            AppRow(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm))
        }.sortedBy { it.label.lowercase() }
    }

    data class AppRow(val pkg: String, val label: String, val icon: Drawable)

    private inner class Adapter(val items: List<AppRow>) : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.iv_app_icon)
            val name: TextView = v.findViewById(R.id.tv_app_name)
            val pkg: TextView = v.findViewById(R.id.tv_package_name)
            val sw: MaterialSwitch = v.findViewById(R.id.sw_app_toggle)
            val root: View = v
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val a = items[position]
            holder.icon.setImageDrawable(a.icon)
            holder.name.text = a.label
            holder.pkg.text = a.pkg
            holder.sw.isChecked = PolicyPrefs.isBypassed(this@BypassAppsActivity, a.pkg)
            holder.root.setOnClickListener {
                val now = !holder.sw.isChecked
                holder.sw.isChecked = now
                PolicyPrefs.setBypassed(this@BypassAppsActivity, a.pkg, now)
                MdmVpnService.reconfigure(this@BypassAppsActivity)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
    override fun onDestroy() { super.onDestroy(); scope.cancel() }
}
