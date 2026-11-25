# Activity and provider are kept automatically via the manifest.
-allowaccessmodification
-repackageclasses
-keep class x.vladgba.files.ShizukuFileService { *; }
-dontwarn org.bouncycastle.**
# 7z / RAR readers: optional codecs and desktop-only APIs they reference are never reached on Android.
-dontwarn org.apache.commons.compress.**
-dontwarn org.apache.commons.io.**
-dontwarn org.apache.commons.lang3.**
-dontwarn org.apache.commons.codec.**
-dontwarn com.github.junrar.**
-dontwarn org.tukaani.xz.**
-dontwarn org.brotli.dec.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.objectweb.asm.**
-dontwarn java.nio.file.**
