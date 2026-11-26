# Office editor bridge is called from packaged JavaScript by method name.
-keepclassmembers class com.nasmount.mobile.** {
    @android.webkit.JavascriptInterface <methods>;
}

# SSHJ / JCIFS use JCA. Bouncy Castle registers provider implementations and
# algorithm mappings through names resolved dynamically by the Java security API.
# Keep the provider entry points but allow optimization; leave the core crypto
# implementation and the rest of the app eligible for R8 shrinking/renaming.
-keep,allowoptimization class org.bouncycastle.jcajce.provider.** { *; }
-keep,allowoptimization class org.bouncycastle.jce.provider.** { *; }
-keep,allowoptimization class org.bouncycastle.pqc.jcajce.provider.** { *; }

# The separately compiled AOT engine reaches embedding/JNI callbacks and
# generated registrants by name. Preserve this boundary in Play release;
# the rest of the application and dependency graph still use R8.
# Deferred-component Play delivery is not enabled in this add-to-app
# project. Leave the optional split-install implementation shrinkable;
# keeping it would require unused legacy Play Core classes.
-keep class !io.flutter.embedding.android.FlutterPlayStoreSplitApplication*,!io.flutter.embedding.engine.deferredcomponents.PlayStoreDeferredComponentManager*,io.flutter.** { *; }

# Temporary internal R8 isolation trial: preserve app code and the
# AndroidX startup entry points; other library code can still be optimized.
# Restore/narrow this after the fatal device trace has been diagnosed.
-keep class com.nasmount.mobile.** { *; }
-keep class androidx.startup.** { *; }
-keep class com.google.android.gms.ads.MobileAdsInitProvider { *; }

# Room 2.2.5 (transitive WorkManager 2.7.0) supplies only a class keep rule.
# R8 full mode otherwise removes its reflectively called no-arg constructor.
# Reproduced on API 36: WorkManagerInitializer -> Room WorkDatabase failure.
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}
