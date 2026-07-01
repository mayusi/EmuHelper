package io.github.mayusi.emuhelper.di

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.mayusi.emuhelper.BuildConfig
import io.github.mayusi.emuhelper.data.safety.SecurityScanner
import io.github.mayusi.emuhelper.data.source.RemoteSource
import io.github.mayusi.emuhelper.data.source.SourceHealthChecker
import io.github.mayusi.emuhelper.data.storage.AuthStore
import io.github.mayusi.emuhelper.platform.AppInfo
import io.github.mayusi.emuhelper.platform.CookieStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

// =====================================================================================
// Phase 2 (Windows port): the PersistentCookieJar class moved to :shared commonMain (its logic is
// pure Kotlin + OkHttp). Only its BACKING STORE is platform-specific; on Android that is the
// EncryptedSharedPreferences-backed [AndroidEncryptedCookieStore] below, injected via [CookieStore].
// =====================================================================================

/**
 * Android backing store for the shared [io.github.mayusi.emuhelper.di.PersistentCookieJar]: persists
 * the jar's already-serialized cookie set into ONE EncryptedSharedPreferences StringSet (AES-256,
 * app-private) — byte-identical persistence to the pre-Phase-2 jar (same prefs file + key).
 *
 * Mirrors the old jar's robustness: a transient KeyStore failure returns null ONCE (no plaintext
 * fallback, no permanent memoized failure) — only a SUCCESSFUL handle is cached.
 */
class AndroidEncryptedCookieStore(context: Context) : CookieStore {
    private val appContext = context.applicationContext

    @Volatile private var cachedPrefs: SharedPreferences? = null
    private val prefsLock = Any()
    private val prefs: SharedPreferences?
        get() {
            cachedPrefs?.let { return it }
            synchronized(prefsLock) {
                cachedPrefs?.let { return it }
                val opened = openPrefs(appContext)
                if (opened != null) cachedPrefs = opened
                return opened
            }
        }

    private fun openPrefs(ctx: Context): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx,
            "emuhelper_cookies",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.w("EmuHelper", "Secure cookie store unavailable; cookies will not persist this session", e)
        null
    }

    override fun load(): Set<String> = try {
        prefs?.getStringSet(KEY_COOKIES, emptySet()) ?: emptySet()
    } catch (e: Exception) {
        Log.w("EmuHelper", "Loading cookies failed", e); emptySet()
    }

    override fun save(cookies: Set<String>) {
        try { prefs?.edit()?.putStringSet(KEY_COOKIES, cookies)?.apply() }
        catch (e: Exception) { Log.w("EmuHelper", "Persisting cookies failed", e) }
    }

    override fun clear() {
        try { prefs?.edit()?.remove(KEY_COOKIES)?.apply() } catch (_: Exception) {}
    }

    companion object { private const val KEY_COOKIES = "app_cookies_v1" }
}


@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Shared [Json] instance for DataStore serialisation (GameListStore, HistoryStore).
     * Uses [ignoreUnknownKeys] so both stores can evolve their models without breaking
     * existing persisted data.
     */
    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /**
     * Android backing store for the shared cookie jar (EncryptedSharedPreferences StringSet).
     * The jar LOGIC lives in :shared; only this persistence is platform-specific.
     */
    @Provides
    @Singleton
    fun provideCookieStore(@ApplicationContext context: Context): CookieStore =
        AndroidEncryptedCookieStore(context)

    @Provides
    @Singleton
    fun provideCookieJar(cookieStore: CookieStore): PersistentCookieJar = PersistentCookieJar(cookieStore)

    /**
     * Build facts the portable RemoteSource needs (replaces its old BuildConfig reference).
     */
    @Provides
    @Singleton
    fun provideAppInfo(): AppInfo = AppInfo(versionName = BuildConfig.VERSION_NAME, debug = BuildConfig.DEBUG)

    /**
     * RemoteSource moved to :shared and no longer carries @Inject, so Hilt can't auto-construct it —
     * provide it explicitly. AuthStore implements the [io.github.mayusi.emuhelper.platform.AuthCredentials]
     * seam RemoteSource asks for. Every injection site in :app (DownloadManager + the ViewModels) is
     * unchanged — they still inject a RemoteSource of the same package + public API.
     */
    @Provides
    @Singleton
    fun provideRemoteSource(
        okHttpClient: OkHttpClient,
        cookieJar: PersistentCookieJar,
        authStore: AuthStore,
        appInfo: AppInfo,
    ): RemoteSource = RemoteSource(okHttpClient, cookieJar, authStore, appInfo)

    /**
     * SourceHealthChecker moved to :shared and no longer carries @Inject, so Hilt can't
     * auto-construct it — provide it explicitly (desktop constructs it by hand the same way).
     */
    @Provides
    @Singleton
    fun provideSourceHealthChecker(okHttpClient: OkHttpClient): SourceHealthChecker =
        SourceHealthChecker(okHttpClient)

    /**
     * SECURITY SCANNER (bonus, best-effort — see :shared's SecurityScanner kdoc). DI-free by design
     * in :shared, so :app provides it explicitly here (same pattern as SourceHealthChecker above) with
     * the default bundled (offline) hash store. DownloadManager injects this and scans each finished
     * download; a scan error never fails the download (see DownloadManager.scanDownloadedFile()).
     */
    @Provides
    @Singleton
    fun provideSecurityScanner(): SecurityScanner = SecurityScanner()

    @Provides
    @Singleton
    fun provideOkHttpClient(cookieJar: PersistentCookieJar): OkHttpClient {
        // Raise OkHttp's tiny default maxRequestsPerHost=5 so multi-connection downloads
        // work — but keep a SANE backstop. The DownloadManager already hard-caps total
        // connections to 24; these limits sit just above that so a runaway can't spawn
        // hundreds of sockets (which thermally crashed a handheld). Do NOT set these huge.
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 40
            maxRequestsPerHost = 32
        }
        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            // WARM-LANE REUSE (v3 laned engine): the durable resource is a small set of warm h2
            // connections, ~2 PINNED per mirror, reused for every 8 MB chunk on that lane. For
            // OkHttp to reuse the SAME warm connection across chunks, the idle connection must
            // survive the brief gap between one chunk finishing and the next ranged GET starting on
            // that lane. The old pool (24 idle, 30s keep-alive) evicted aggressively under churn and
            // the lane could pay a fresh TCP/TLS handshake + h2 slow-start on the next chunk. Widen
            // the keep-alive to 5 MINUTES so a pinned lane's connection stays warm for the whole
            // file; 8 max-idle is plenty (~2/host × up to 3 mirrors = ~6 warm sockets) without
            // hoarding. This is the change that lets the laned engine kill the per-chunk slow-start
            // tax (measured 8× median-vs-peak gap on the old per-chunk-host-repick engine).
            .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
            // Hygiene: state the protocol preference explicitly. IA serves HTTP/2 end-to-end and
            // OkHttp already negotiates h2 via ALPN, but pinning the list documents intent and keeps
            // h2 (multiplexed, low-handshake) first with http/1.1 as the graceful fallback.
            .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(cookieJar)
            .build()
    }
}
