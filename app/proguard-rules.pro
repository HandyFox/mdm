-keep class com.mdmagent.receiver.** { *; }
-keep class com.mdmagent.service.** { *; }
-keep class com.mdmagent.util.PasswordManager { *; }
-keepclassmembers class * extends android.app.admin.DeviceAdminReceiver { *; }

# Fix R8 missing classes for javax.annotation (often from Tink/Security-Crypto)
-dontwarn javax.annotation.**