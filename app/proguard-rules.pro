# Default ProGuard rules for Android
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception

# Keep Retrofit interfaces
-keepattributes Signature
-keepattributes Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Keep Gson models
-keep class com.lexiread.app.data.model.** { *; }

# Keep Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontnote okhttp3.internal.platform.**

# PdfBox can optionally use Gemalto's JPEG2000 decoder. It is not bundled with the Android
# reader; PDFBox already falls back when a JPX image is encountered, so R8 must not treat the
# optional integration as a missing runtime dependency.
-dontwarn com.gemalto.jp2.JP2Decoder
