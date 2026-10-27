package android.content
class Intent { fun addFlags(flags: Int) = this; companion object { const val FLAG_ACTIVITY_NEW_TASK = 1 } }
class Preferences { fun getString(key: String, default: String?): String? = "player" }
class PackageManager {
 fun getLeanbackLaunchIntentForPackage(name: String): Intent? = null
 fun getLaunchIntentForPackage(name: String): Intent? = Intent()
}
open class Context {
 val applicationContext get() = this
 val packageManager = PackageManager()
 fun getSharedPreferences(name: String, mode: Int) = Preferences()
 fun startActivity(intent: Intent) { launches++; if (rejectLaunch) throw IllegalStateException("Synthetic launch rejection") }
 companion object { const val MODE_PRIVATE = 0; var launches = 0; var rejectLaunch = false }
}
