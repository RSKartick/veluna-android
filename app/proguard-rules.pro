# Add project specific ProGuard rules here.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Firestore maps fields by name. R8 must not rename or strip these.
-keep class com.example.data.cloud.Cloud** { *; }
-keepclassmembers class com.example.data.cloud.Cloud** {
    <init>(...);
}

# kotlinx-serialization models & backup payloads
-keep class com.example.model.** { *; }
-keep class com.example.data.local.VelunaUserPrefs { *; }
-keep class com.example.data.repository.VelunaBackupPayload { *; }
-keep class com.example.data.network.QueuedScrobbleTrack { *; }
-keep class com.example.data.repository.Backup** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <methods>;
}
