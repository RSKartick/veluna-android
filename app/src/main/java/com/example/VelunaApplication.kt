package com.example

import android.app.Application
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
}

class VelunaApplication : Application() {
    lateinit var container: VelunaAppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = VelunaAppContainer(this)
        container.scrobbleClient.registerConnectivityFlush(this)
        appScope.launch {
            container.prefsRepository.migrateSecretsAndPruneLegacyKeys()
            container.scrobbleClient.flushPendingScrobbles()
        }
    }
}
