# Device Manager — Android MDM Agent

A consent-based, locally-administered device management app for Android,
tuned for locked-down fleet handsets (incl. Kyocera DuraXV / rugged devices).
The UI is a modern Material 3 dark theme that works on **touch and non-touch
devices** (full D-pad / hardware-key navigation) and on **small screens**.

---

## Features

### Access
- **Administrator password** gate on every launch (SHA-256 in
  `EncryptedSharedPreferences`). First run sets it up; QR Device-Owner
  provisioning forces setup immediately.

### Security (all Device-Owner enforced where noted)
- Block **all app installation** (Play Store + sideloading) or unknown sources only
- Disable **Developer Options & ADB**
- Disable **Safe Boot** *(Device Owner)*
- Disable **Camera**
- Disable **Screenshots** *(Device Owner)*
- Block **Factory Reset** *(Device Owner)*
- **Block mobile hotspot / tethering** — NEW (`DISALLOW_CONFIG_TETHERING`)
- **Prevent wipe on wrong PIN** — NEW. Sets the failed-unlock-before-wipe
  threshold impossibly high so the Android credential wipe never triggers.
  *(See the OEM caveat below.)*
- **Lock device now**

### Web Whitelist (VPN) — replaces the old proxy/web-filter
- A local **VpnService** that **blocks ALL web traffic except the domains you
  add**. Subdomains are allowed automatically (`example.com` ⇒ `www.example.com`).
- Strict, full-packet filtering: it inspects **DNS**, **TLS SNI**, and the
  **HTTP Host** header, forwards only whitelisted destinations (and the IPs they
  resolve to), and drops everything else — including raw-IP connections. IPv6 is
  captured and dropped so it can't slip around the filter.
- **Per-app bypass**: pick apps that are routed *around* the whitelist and keep
  full internet access (`addDisallowedApplication`).
- On a Device Owner the filter is set as an **always-on VPN** (no consent prompt,
  auto-restart after reboot). Otherwise the standard one-time VPN consent is used.
- Optional **activity log** of allowed/blocked domains.

### Kyocera & Device Controls
- **Kyocera OEMConfig** restrictions via the *Kyocera Device Config* plugin
  (`jp.kyocera.enterprisedeviceconfig`) — recovery mode, USB debug, OTA, etc.,
  discovered live from the plugin's manifest. *(Device Owner + plugin required.)*
- **Disable screen lock** (removes the PIN so an OEM wipe-on-failed-unlock has
  nothing to count).
- **Disable touchscreen (keypad only)** — NEW. A transparent full-screen overlay
  swallows every touch while hardware keys / D-pad keep working. No root
  required; works on any device (e.g. the LG Wine). Needs the "Display over
  other apps" permission.

### App Manager
- List installed apps and enable/disable (hide) them.

### Unenroll
- Password-protected removal of all restrictions + Device Owner.

> **Removed in this version:** the global-proxy "proxy bridge" and the cloud
> enrollment / remote-dashboard sync layer. This app is now a self-contained,
> local, password-gated tool.

---

## Build

### GitHub Actions (easiest — produces an APK artifact)
Every push runs `.github/workflows/build.yml`, which builds a debug APK on a
runner that has the Android SDK and uploads it as the **`mdm-debug-apk`**
artifact. Open the run under the **Actions** tab and download it from there.
(You can also trigger it manually via *Run workflow*.)

### Android Studio
1. **File → Open** the repo root.
2. Let Gradle sync (wrapper pins Gradle 8.2 / AGP 8.2, JDK 17, SDK 34).
3. **Build → Build APK(s)**, or Run onto a device.

### Command line
```bash
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

---

## Device Owner (recommended)
Most enforcement (app hiding, hotspot/factory-reset restrictions, always-on VPN,
Kyocera OEMConfig, screen-lock removal) requires **Device Owner**. After a
factory reset, before adding any account:
```bash
adb shell dpm set-device-owner com.mdmagent.device/com.mdmagent.receiver.MdmAdminReceiver
```
> Note the component string: the package is `com.mdmagent.device` (the
> `applicationId`) while the receiver class is `com.mdmagent.receiver.MdmAdminReceiver`
> (the `namespace`). Use the full class path — the `/.receiver.…` shorthand
> expands against the package and resolves to the wrong class ("Unknown admin").
> Device Owner also requires a device with **no accounts added** and a single user.
Or provision via QR (`qr_params.txt` holds the checksums — **regenerate these
for each newly-built APK**, since the signature/package checksums change).

---

## Important caveats
- **Touchscreen disable** is a software overlay (no supported Android API
  disables the digitizer without root). It blocks touches reliably but is not a
  hardware cut-off; system bars may still accept a swipe.
- **Wrong-PIN wipe**: this app raises the *Android DevicePolicy* threshold.
  Some OEM firmware (incl. certain Kyocera builds) enforce their own
  wipe-after-N below the Android layer, which no app can change. On those
  devices the reliable fix is **disabling the screen lock** so there is no PIN
  to fail.
- The VPN filter is a compact userspace NAT, not a full TCP stack; it targets
  low-throughput locked devices and should be validated on the target hardware.

---

## Managed Device Notice
The app and its persistent notifications make clear the device is managed —
intentional, for ethical and legal compliance.
