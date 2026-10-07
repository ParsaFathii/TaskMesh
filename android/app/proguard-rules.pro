# TaskMesh Android client — R8/ProGuard rules.
# Minification is disabled for the 0.1.0 release (see app/build.gradle.kts), but
# these rules are kept so enabling isMinifyEnabled later stays safe.

# --- kotlinx.serialization ---------------------------------------------------
# Keep generated serializers for the app's @Serializable models and the library
# serializer lookup used when (de)serializing generic types such as PageDto<T>.
-keepattributes *Annotation*, InnerClasses, Signature

-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.taskmesh.app.**$$serializer { *; }
-keepclassmembers class com.taskmesh.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.taskmesh.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Retrofit ----------------------------------------------------------------
# Retrofit and OkHttp ship consumer rules in their artifacts; nothing extra is
# needed beyond keeping the API interface (kept automatically because it is
# referenced directly from code).
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
