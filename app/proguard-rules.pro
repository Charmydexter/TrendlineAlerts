# Retrofit
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keep,includedescriptorclasses class com.trendlinealerts.app.data.remote.**$$serializer { *; }
-keepclassmembers class com.trendlinealerts.app.data.remote.** {
    *** Companion;
}
-keepclasseswithmembers class com.trendlinealerts.app.data.remote.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room entities
-keep class com.trendlinealerts.app.data.local.** { *; }