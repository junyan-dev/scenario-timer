# Keep Material Components (Chip, BottomSheetDialog, etc.)
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# Keep ViewBinding generated classes
-keep class dev.junyan.scenariotimer.databinding.** { *; }

# Keep data class used in JSON serialization
-keep class dev.junyan.scenariotimer.TimerScene { *; }
