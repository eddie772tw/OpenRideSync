package android.app
class ActivityManager {
 class RunningAppProcessInfo { var importance = IMPORTANCE_FOREGROUND; companion object { const val IMPORTANCE_FOREGROUND = 1 } }
 companion object { fun getMyMemoryState(info: RunningAppProcessInfo) {} }
}
