# RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0

# Modèles sérialisés en JSON (configuration, API iTunes et Radio Browser)
-keep class com.cgexcel.radioclic.model.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
