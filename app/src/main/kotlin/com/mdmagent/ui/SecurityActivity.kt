package com.mdmagent.ui

import android.os.Bundle
import android.os.UserManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mdmagent.databinding.ActivitySecurityBinding
import com.mdmagent.util.DeviceAdminHelper

class SecurityActivity : AppCompatActivity() {

    private lateinit var b: ActivitySecurityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySecurityBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val isAdmin = DeviceAdminHelper.isAdminActive(this)
        val isOwner = DeviceAdminHelper.isDeviceOwner(this)

        b.tvAdminMode.text = when {
            isOwner -> "Device Owner — all restrictions available"
            isAdmin -> "Device Administrator — some restrictions need Device Owner"
            else    -> "Admin not active — activate from the main screen"
        }

        wire(isAdmin, isOwner)
    }

    private fun wire(isAdmin: Boolean, isOwner: Boolean) {
        // Reflect saved state first (silent), then attach handlers.
        b.rowBlockInstall.bind(DeviceAdminHelper.isAppInstallBlocked(this), isAdmin) { on ->
            if (on) DeviceAdminHelper.blockAllAppInstallation(this)
            else DeviceAdminHelper.allowAppInstallation(this)
            toast(if (on) "App installation blocked" else "App installation allowed")
        }

        b.rowBlockUnknown.bind(DeviceAdminHelper.isUnknownSourcesBlocked(this), isOwner) { on ->
            val dpm = DeviceAdminHelper.getDpm(this); val admin = DeviceAdminHelper.getAdmin(this)
            if (on) dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            else dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            toast(if (on) "Sideloading blocked" else "Sideloading allowed")
        }

        b.rowDisableDev.bind(DeviceAdminHelper.isDeveloperOptionsDisabled(this), isOwner) { on ->
            if (on) DeviceAdminHelper.disableDeveloperOptions(this)
            else DeviceAdminHelper.enableDeveloperOptions(this)
            toast(if (on) "Developer options disabled" else "Developer options enabled")
        }

        b.rowSafeBoot.bind(DeviceAdminHelper.isSafeBootDisabled(this), isOwner) { on ->
            if (on) DeviceAdminHelper.disableSafeBoot(this) else DeviceAdminHelper.enableSafeBoot(this)
            toast(if (on) "Safe boot disabled" else "Safe boot enabled")
        }

        b.rowCamera.bind(DeviceAdminHelper.isCameraDisabled(this), isAdmin) { on ->
            if (on) DeviceAdminHelper.disableCamera(this) else DeviceAdminHelper.enableCamera(this)
            toast(if (on) "Camera disabled" else "Camera enabled")
        }

        b.rowScreenshot.bind(DeviceAdminHelper.isScreenCaptureDisabled(this), isOwner) { on ->
            if (on) DeviceAdminHelper.disableScreenCapture(this) else DeviceAdminHelper.enableScreenCapture(this)
            toast(if (on) "Screenshots disabled" else "Screenshots enabled")
        }

        b.rowHotspot.bind(DeviceAdminHelper.isHotspotDisabled(this), isOwner) { on ->
            if (on) DeviceAdminHelper.disableHotspot(this) else DeviceAdminHelper.enableHotspot(this)
            toast(if (on) "Mobile hotspot blocked" else "Mobile hotspot allowed")
        }

        b.rowFactoryReset.bind(DeviceAdminHelper.isFactoryResetDisabled(this), isOwner) { on ->
            if (on) DeviceAdminHelper.disableFactoryReset(this)
            else { DeviceAdminHelper.enableFactoryReset(this); toast("Factory reset allowed") }
            if (on) toast("Factory reset blocked")
        }

        b.rowWipeGuard.bind(DeviceAdminHelper.isWipeGuardEnabled(this), isAdmin) { on ->
            DeviceAdminHelper.setWipeGuard(this, on)
            toast(if (on) "Wipe-on-wrong-PIN prevented" else "Default wipe behaviour restored")
        }

        b.btnLockNow.setOnClickListener {
            if (!isAdmin) { toast("Activate Device Admin first"); return@setOnClickListener }
            DeviceAdminHelper.lockDevice(this)
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
