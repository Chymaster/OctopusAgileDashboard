# Add project specific ProGuard rules here.

# ---- Kotlin Serialization ----
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
}
-keepclasseswithmembers @kotlinx.serialization.Serializable class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.chymaster.octopusagiledashboard.**$$serializer { *; }
-keepclassmembers class com.chymaster.octopusagiledashboard.** {
    *** Companion;
}
-keepclasseswithmembers class com.chymaster.octopusagiledashboard.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- Retrofit ----
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# ---- OkHttp ----
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- Room ----
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *

# ---- Hilt ----
# Hilt-generated code is already kept by its own rules, but keep annotated entry points
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }

# ---- Compose ----
-keep class androidx.compose.** { *; }

# ---- DataStore ----
-keep class androidx.datastore.** { *; }

# ---- Vico (charting) ----
-keep class com.patrykandpatrick.vico.** { *; }

# ---- General ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
