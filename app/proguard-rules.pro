# --- Mint app rules ---

# Entry points referenced from the manifest are kept automatically by R8,
# but keep them explicit for clarity and safety.
-keep class mint.app.MainActivity
-keep class mint.app.SplashActivity
-keep class mint.app.service.DownloadService
-keep class mint.app.service.CancelDownloadReceiver
-keep class mint.app.connection.InstagramLoginActivity
-keep class mint.app.connection.TikTokLoginActivity

# BuildConfig is referenced at runtime for the version check.
-keep class mint.app.BuildConfig {
    public static <fields>;
}

# --- youtubedl-android (com.yausername) ---
# YoutubeDL and its mapper classes are populated reflectively (JSON -> POJO).
-keep class com.yausername.** { *; }

# --- OkHttp / Okio ---
# The application uses OkHttp for TikTok/Instagram/updates and Coil networking.
# Coil 3 and OkHttp ship their own consumer rules; keep these as a fallback.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Compose / Coil ---
# Compose and Coil ship their own rules. Keep Coil's resource lookup helpers.
-dontwarn coil.**
