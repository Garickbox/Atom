# keep defaults

# P2 TLS: BouncyCastle — keep, иначе release-only NoClassDefFoundError
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
