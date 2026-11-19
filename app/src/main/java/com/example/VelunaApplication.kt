package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.size.Precision
import com.example.data.cloud.CloudSyncCoordinator
import com.example.data.cloud.CloudSyncRepository
import com.example.data.local.UserPrefsRepository
import com.example.data.local.VelunaDatabase
import com.example.data.network.InnerTubeClient
import com.example.data.network.LyricsRepository
import com.example.data.network.ScrobbleClient
import com.example.data.repository.TasteEngine
import com.example.data.repository.VelunaRepository
import com.example.playback.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VelunaAppContainer(application: Application) {
    val database: VelunaDatabase = VelunaDatabase.getInstance(application)
    val dao = database.velunaDao()
    val prefsRepository: UserPrefsRepository = UserPrefsRepository(application)
    val innerTubeClient: InnerTubeClient = InnerTubeClient()
    val lyricsRepository: LyricsRepository = LyricsRepository(dao, innerTubeClient)
    val scrobbleClient: ScrobbleClient = ScrobbleClient(
        httpClient = innerTubeClient.httpClient,
        dao = dao,
        prefsRepo = prefsRepository
    )
    val tasteEngine: TasteEngine = TasteEngine(
        dao = dao,
        prefsRepo = prefsRepository,
        innerTube = innerTubeClient
    )
    val repository: VelunaRepository = VelunaRepository(
        context = application,
        dao = dao,
        prefsRepo = prefsRepository,
        innerTube = innerTubeClient,
        database = database
    )
    val cloudSyncRepository: CloudSyncRepository = CloudSyncRepository(application)
    val cloudSyncCoordinator: CloudSyncCoordinator = CloudSyncCoordinator(
        context = application,
        dao = dao,
        prefsRepo = prefsRepository,
        repository = repository,
        cloudRepo = cloudSyncRepository
    )
    val playerController: PlayerController = PlayerController(
        context = application,
        dao = dao,
        repository = repository,
        prefsRepo = prefsRepository,
        innerTube = innerTubeClient,
        scrobbleClient = scrobbleClient,
        tasteEngine = tasteEngine
    )

    init {
        repository.runWithSyncSuppressed = { block ->
            cloudSyncCoordinator.runWithLocalSyncSuppressed(block)
        }
    }
}

class VelunaApplication : Application(), ImageLoaderFactory {
    lateinit var container: VelunaAppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = VelunaAppContainer(this)
        container.scrobbleClient.registerConnectivityFlush(this)
        appScope.launch {
            runCatching { container.prefsRepository.migrateSecretsAndPruneLegacyKeys() }
            runCatching { container.scrobbleClient.flushPendingScrobbles() }
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("veluna_image_cache"))
                    .maxSizeBytes(64L * 1024L * 1024L)
                    .build()
            }
            .respectCacheHeaders(false)
            .allowHardware(true)
            .precision(Precision.INEXACT)
            .crossfade(false)
            .build()
    }
}
