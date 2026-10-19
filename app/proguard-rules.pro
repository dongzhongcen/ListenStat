# ListenStat R8 rules.
# Room ships consumer rules for generated *_Impl classes; keep entities/DAOs' generated code safe.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-keepclassmembers class com.dongzhongcen.listenstat.data.** { *; }

# Notification listener service is referenced from the manifest (kept by AAPT rules); keep its name stable.
-keep class com.dongzhongcen.listenstat.service.MediaTrackService { *; }

# Custom views inflated from XML (AAPT keeps constructors; keep explicitly for clarity).
-keep class com.dongzhongcen.listenstat.ui.BarChartView { <init>(...); }
-keep class com.dongzhongcen.listenstat.ui.NotchedCardView { <init>(...); }

# Better stack traces in bug reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
