# Keep Room generated implementations
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# SQLCipher native access
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**

# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.pam.recap.**$$serializer { *; }
-keepclassmembers class com.pam.recap.** { *** Companion; }
-keepclasseswithmembers class com.pam.recap.** { kotlinx.serialization.KSerializer serializer(...); }

# Enum constant names are *persisted*: converters store `enum.name` and DAO SQL compares literals
# such as 'CREDIT' / 'POSTED'. R8 renames enum constants by default (CREDIT -> e, DEBIT -> f),
# which would silently stop every stored-value query from matching. Keep the names.
-keepclassmembers enum com.pam.recap.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Compose navigation keeps route serializers via kotlinx.serialization; keep the generated
# serializers of the route classes even if a destination is only reached dynamically.
-keep,includedescriptorclasses class com.pam.recap.ui.navigation.**$$serializer { *; }
-keepclassmembers class com.pam.recap.ui.navigation.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# G2/CR-001: javax.mail resolves the IMAP store and MIME handlers by name from
# META-INF/javamail.providers, so R8 must not strip or rename these classes.
-keep class javax.mail.** { *; }
-keep class com.sun.mail.** { *; }
-dontwarn javax.mail.**
-dontwarn com.sun.mail.**
