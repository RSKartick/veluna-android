package com.example.data.cloud

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.data.local.BlockedArtistEntity
import com.example.data.local.LikedSongEntity
import com.example.data.local.LocalPlaylistEntity
import com.example.data.local.LocalPlaylistTrackEntity
import com.example.data.local.SyncEntityTypes
import com.example.data.local.SyncTombstoneEntity
import com.example.data.local.UserPrefsRepository
import com.example.data.local.VelunaDao
import com.example.data.local.VelunaUserPrefs
import com.example.data.repository.VelunaRepository
import com.example.model.AudioQuality
import com.example.model.LOCAL_SONG_PREFIX
import com.example.model.SongItem
import com.example.model.TasteProfile
import com.example.model.TasteSeed
import com.example.model.ThemeMode
import com.example.model.VelunaThemePreset
import com.example.util.SecurityValidators
import com.example.util.suspendRunCatching
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.withTimeoutOrNull
import com.example.data.cloud.GoogleAuthHelper.findActivity
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

@Immutable
data class CloudAuthUiState(
    val isSignedIn: Boolean = false,
    val uid: String? = null,
    val displayName: String? = null,
    val email: String? = null,
    val photoUrl: String? = null,
    val isAuthBusy: Boolean = false,
    val isSyncing: Boolean = false,
    val syncStatus: String? = null,
    val lastSyncedAtMs: Long? = null,
    val errorMessage: String? = null,
    val pendingUnsyncedCount: Int = 0,
    val pendingSignOutConfirmCount: Int? = null,
    val isDeletingAccount: Boolean = false,
    val deleteAccountError: String? = null,
    val needsRetryAuthDelete: Boolean = false
)

class CloudSyncCoordinator(
    private val context: Context,
    private val dao: VelunaDao,
    private val prefsRepo: UserPrefsRepository,
    private val repository: VelunaRepository,
    private val cloudRepo: CloudSyncRepository,
    private val auth: FirebaseAuth = Firebase.auth
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val suppressLocalPushCount = AtomicInteger(0)

    private val _authState = MutableStateFlow(buildUiState(auth.currentUser))
    val authState: StateFlow<CloudAuthUiState> = _authState.asStateFlow()

    @Volatile
    private var activeUserId: String? = null
    private var autoSignInAttempted = false
    private var autoSignInJob: Job? = null
    private val syncLifecycleLock = Any()
    private val watcherJobs = mutableListOf<Job>()
    private var retryJob: Job? = null
    private var retryAttempt = 0

    // Local change tracking baselines
    private val syncedLikedIds = mutableSetOf<String>()
    private val syncedHiddenNames = mutableSetOf<String>()
    private val syncedSeedIds = mutableSetOf<String>()
    private val syncedPlaylistTimestamps = mutableMapOf<String, String>()
    private val localPlaylistToCloudKey = mutableMapOf<Long, String>()

    // Track failed operations so honest sync status never masks partial failures
    private val failedCollections = mutableMapOf<String, String>()

    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        _authState.update { cur ->
            if (user == null) {
                CloudAuthUiState(isSignedIn = false)
            } else {
                cur.copy(
                    isSignedIn = true,
                    uid = user.uid,
                    displayName = user.displayName ?: cur.displayName,
                    email = user.email ?: cur.email,
                    photoUrl = SecurityValidators.sanitizeHttpsUrl(user.photoUrl?.toString(), 500) ?: cur.photoUrl,
                    isAuthBusy = false
                )
            }
        }

        synchronized(syncLifecycleLock) {
            if (user == null) {
                stopSyncForSignedOutLocked()
            } else if (activeUserId != user.uid) {
                startSyncForUserLocked(user)
            }
        }
    }

    init {
        auth.addAuthStateListener(authStateListener)
        auth.currentUser?.let { existing ->
            synchronized(syncLifecycleLock) {
                if (activeUserId != existing.uid) {
                    startSyncForUserLocked(existing)
                }
            }
        }
    }

    private fun buildUiState(user: FirebaseUser?): CloudAuthUiState {
        return if (user == null) {
            CloudAuthUiState(isSignedIn = false)
        } else {
            CloudAuthUiState(
                isSignedIn = true,
                uid = user.uid,
                displayName = user.displayName,
                email = user.email,
                photoUrl = SecurityValidators.sanitizeHttpsUrl(user.photoUrl?.toString(), 500)
            )
        }
    }

    fun attemptAutoSignInOnLaunch(activityContext: Context) {
        val activityRef = WeakReference(activityContext.findActivity())
        synchronized(syncLifecycleLock) {
            if (autoSignInAttempted || auth.currentUser != null || _authState.value.isAuthBusy) return
            autoSignInAttempted = true
            autoSignInJob?.cancel()
            autoSignInJob = scope.launch {
                val activity = activityRef.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
                    ?: return@launch
                GoogleAuthHelper.attemptAutoSignIn(activity)
            }
        }
    }

    fun signIn(activityContext: Context) {
        val activityRef = WeakReference(activityContext.findActivity())
        synchronized(syncLifecycleLock) {
            if (_authState.value.isAuthBusy) return
            autoSignInJob?.cancel()
            autoSignInJob = null
            _authState.update { it.copy(isAuthBusy = true, errorMessage = null) }
        }
        scope.launch {
            try {
                val activity = activityRef.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
                if (activity == null) {
                    _authState.update {
                        it.copy(
                            isAuthBusy = false,
                            errorMessage = "Unable to open Google Sign-In from this screen."
                        )
                    }
                    return@launch
                }
                when (val outcome = GoogleAuthHelper.signInWithGoogle(activity)) {
                    is GoogleSignInOutcome.Success -> {
                        _authState.update {
                            it.copy(
                                isSignedIn = true,
                                uid = outcome.user.uid,
                                displayName = outcome.user.displayName,
                                email = outcome.user.email,
                                photoUrl = SecurityValidators.sanitizeHttpsUrl(outcome.user.photoUrl?.toString(), 500),
                                isAuthBusy = false,
                                errorMessage = null
                            )
                        }
                        synchronized(syncLifecycleLock) {
                            if (activeUserId != outcome.user.uid) {
                                startSyncForUserLocked(outcome.user)
                            }
                        }
                    }
                    GoogleSignInOutcome.Cancelled -> {
                        _authState.update { it.copy(isAuthBusy = false) }
                    }
                    is GoogleSignInOutcome.Error -> {
                        _authState.update {
                            it.copy(
                                isAuthBusy = false,
                                errorMessage = outcome.message
                            )
                        }
                    }
                }
            } finally {
                _authState.update { it.copy(isAuthBusy = false) }
            }
        }
    }

    /**
     * Flushes pending sync for up to 10s before signing out.
     * If changes remain unsynced, asks the user to confirm via [pendingSignOutConfirmCount].
     */
    fun signOut() {
        if (_authState.value.isAuthBusy) return
        _authState.update { it.copy(isAuthBusy = true, errorMessage = null, pendingSignOutConfirmCount = null) }
        scope.launch {
            val user = auth.currentUser
            if (user != null) {
                withTimeoutOrNull(10_000L) {
                    runBidirectionalMerge(user)
                }
            }
            val remaining = countUnsyncedChanges()
            if (remaining > 0) {
                _authState.update {
                    it.copy(
                        isAuthBusy = false,
                        pendingUnsyncedCount = remaining,
                        pendingSignOutConfirmCount = remaining
                    )
                }
            } else {
                performLocalWipeAndSignOut(signOutAuth = true)
            }
        }
    }

    fun dismissSignOutConfirm() {
        _authState.update { it.copy(pendingSignOutConfirmCount = null, isAuthBusy = false) }
    }

    fun confirmSignOutDiscard() {
        _authState.update { it.copy(pendingSignOutConfirmCount = null, isAuthBusy = true) }
        scope.launch {
            performLocalWipeAndSignOut(signOutAuth = true)
        }
    }

    /**
     * Deletes all Firestore user data and the Firebase Auth account in strict safe order:
     * Re-auth (if stale) -> Firestore batch wipe -> Auth user delete -> local account wipe.
     */
    fun deleteAccount(activityContext: Context) {
        if (_authState.value.isDeletingAccount) return
        val user = auth.currentUser ?: return
        val activityRef = WeakReference(activityContext.findActivity())
        _authState.update { it.copy(isDeletingAccount = true, deleteAccountError = null) }

        scope.launch {
            try {
                // Step 1: Ensure recent login before touching Firestore data so failed re-auth never leaves partial deletes
                if (!_authState.value.needsRetryAuthDelete && GoogleAuthHelper.needsRecentSignIn(user)) {
                    val reauthActivity = activityRef.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
                    if (reauthActivity == null) {
                        _authState.update {
                            it.copy(
                                isDeletingAccount = false,
                                deleteAccountError = "Re-authentication requires an active screen."
                            )
                        }
                        return@launch
                    }
                    when (val reauth = GoogleAuthHelper.reauthenticateWithGoogle(reauthActivity, user)) {
                        is GoogleSignInOutcome.Success -> Unit
                        GoogleSignInOutcome.Cancelled -> {
                            _authState.update { it.copy(isDeletingAccount = false) }
                            return@launch
                        }
                        is GoogleSignInOutcome.Error -> {
                            _authState.update {
                                it.copy(isDeletingAccount = false, deleteAccountError = reauth.message)
                            }
                            return@launch
                        }
                    }
                }

                // Step 2: Delete Firestore documents in batches of 400 (idempotent)
                if (!_authState.value.needsRetryAuthDelete) {
                    stopSyncForSignedOut()
                    try {
                        cloudRepo.deleteAllUserData(user.uid)
                    } catch (e: FirebaseFirestoreException) {
                        val msg = if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                            "Permission denied deleting cloud data. Please sign in again and retry."
                        } else {
                            SecurityValidators.safeUserErrorMessage(e, "Failed to erase cloud data.")
                        }
                        _authState.update { it.copy(isDeletingAccount = false, deleteAccountError = msg) }
                        return@launch
                    } catch (e: Exception) {
                        _authState.update {
                            it.copy(
                                isDeletingAccount = false,
                                deleteAccountError = SecurityValidators.safeUserErrorMessage(
                                    e,
                                    "Network error deleting cloud data. Retry to finish."
                                )
                            )
                        }
                        return@launch
                    }
                }

                // Step 3: Delete Firebase Auth user (retryable if session just expired)
                try {
                    GoogleAuthHelper.deleteAuthUser(user)
                } catch (_: FirebaseAuthRecentLoginRequiredException) {
                    val reauthActivity = activityRef.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
                    if (reauthActivity == null) {
                        _authState.update {
                            it.copy(
                                isDeletingAccount = false,
                                needsRetryAuthDelete = true,
                                deleteAccountError = "Cloud data erased. Re-open Account settings to confirm deletion."
                            )
                        }
                        return@launch
                    }
                    when (val reauth = GoogleAuthHelper.reauthenticateWithGoogle(reauthActivity, user)) {
                        is GoogleSignInOutcome.Success -> {
                            suspendRunCatching { GoogleAuthHelper.deleteAuthUser(user) }.onFailure { err ->
                                _authState.update {
                                    it.copy(
                                        isDeletingAccount = false,
                                        needsRetryAuthDelete = true,
                                        deleteAccountError = SecurityValidators.safeUserErrorMessage(
                                            err,
                                            "Cloud data erased, but failed to delete Auth account. Tap Retry."
                                        )
                                    )
                                }
                                return@launch
                            }
                        }
                        GoogleSignInOutcome.Cancelled -> {
                            _authState.update {
                                it.copy(
                                    isDeletingAccount = false,
                                    needsRetryAuthDelete = true,
                                    deleteAccountError = "Cloud data erased. Confirm Google re-authentication to finish deleting your account."
                                )
                            }
                            return@launch
                        }
                        is GoogleSignInOutcome.Error -> {
                            _authState.update {
                                it.copy(
                                    isDeletingAccount = false,
                                    needsRetryAuthDelete = true,
                                    deleteAccountError = reauth.message
                                )
                            }
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    _authState.update {
                        it.copy(
                            isDeletingAccount = false,
                            needsRetryAuthDelete = true,
                            deleteAccountError = SecurityValidators.safeUserErrorMessage(
                                e,
                                "Cloud data erased, but Auth deletion failed. Retry."
                            )
                        )
                    }
                    return@launch
                }

                // Step 4: Wipe local account tables, secrets, and user prefs keys (keep downloads & local tracks)
                performLocalWipeAndSignOut(signOutAuth = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _authState.update {
                    it.copy(
                        isDeletingAccount = false,
                        deleteAccountError = SecurityValidators.safeUserErrorMessage(
                            e,
                            "Account deletion failed."
                        )
                    )
                }
            }
        }
    }

    fun clearDeleteAccountError() {
        _authState.update { it.copy(deleteAccountError = null) }
    }

    private suspend fun performLocalWipeAndSignOut(signOutAuth: Boolean) {
        stopSyncForSignedOut()
        cloudRepo.clearSessionCache()
        dao.clearAccountData()
        prefsRepo.resetUserKeys()
        if (signOutAuth) {
            GoogleAuthHelper.signOut(context.applicationContext)
        }
        _authState.value = CloudAuthUiState(isSignedIn = false)
    }

    fun syncNow() {
        val user = auth.currentUser ?: return
        scope.launch {
            runBidirectionalMerge(user)
        }
    }

    fun clearError() {
        _authState.update { it.copy(errorMessage = null) }
    }

    private fun stopSyncForSignedOut() {
        synchronized(syncLifecycleLock) {
            stopSyncForSignedOutLocked()
        }
    }

    private fun stopSyncForSignedOutLocked() {
        activeUserId = null
        retryJob?.cancel()
        retryJob = null
        retryAttempt = 0
        watcherJobs.forEach { it.cancel() }
        watcherJobs.clear()
        syncedLikedIds.clear()
        syncedHiddenNames.clear()
        syncedSeedIds.clear()
        syncedPlaylistTimestamps.clear()
        localPlaylistToCloudKey.clear()
        failedCollections.clear()
    }

    private fun startSyncForUserLocked(user: FirebaseUser) {
        if (activeUserId == user.uid && watcherJobs.isNotEmpty()) return
        watcherJobs.forEach { it.cancel() }
        watcherJobs.clear()
        retryJob?.cancel()
        retryJob = null
        retryAttempt = 0
        activeUserId = user.uid

        val mainJob = scope.launch {
            runBidirectionalMerge(user)
            synchronized(syncLifecycleLock) {
                if (activeUserId == user.uid && auth.currentUser?.uid == user.uid) {
                    launchWatchersLocked(user)
                }
            }
        }
        watcherJobs.add(mainJob)
    }

    private suspend fun countUnsyncedChanges(): Int {
        var pending = failedCollections.size
        if (pending > 0) return pending
        val localLikes = dao.getLikedSongsOnce().count { !it.videoId.startsWith(LOCAL_SONG_PREFIX) }
        val unsyncedLikes = (localLikes - syncedLikedIds.size).coerceAtLeast(0)
        pending += unsyncedLikes
        val tombCount = dao.getAllTombstones().size
        return (pending + tombCount).coerceAtLeast(if (failedCollections.isNotEmpty()) 1 else 0)
    }

    private suspend fun scrubLocalLikesOnceIfNeeded() {
        if (prefsRepo.isLocalLikesScrubbed()) return
        val localIds = dao.getLocalLikedVideoIds()
        if (localIds.isNotEmpty()) {
            dao.deleteLocalLikedSongs()
            suspendRunCatching { cloudRepo.purgeLocalLikedDocsFromCloud(localIds) }
        }
        prefsRepo.markLocalLikesScrubbed()
    }

    private suspend fun runBidirectionalMerge(user: FirebaseUser) {
        syncMutex.withLock {
            if (auth.currentUser?.uid != user.uid) return@withLock
            _authState.update {
                it.copy(isSyncing = true, syncStatus = "Syncing…", errorMessage = null)
            }
            suppressLocalPushCount.incrementAndGet()
            try {
                scrubLocalLikesOnceIfNeeded()

                val now = System.currentTimeMillis()
                val tombstoneCutoff = now - TOMBSTONE_RETENTION_MS
                dao.purgeOldTombstones(tombstoneCutoff)

                val watermarks = SyncWatermarks(
                    likedMs = prefsRepo.getSyncWatermark(UserPrefsRepository.WATERMARK_LIKED),
                    playlistsMs = prefsRepo.getSyncWatermark(UserPrefsRepository.WATERMARK_PLAYLISTS),
                    seedsMs = prefsRepo.getSyncWatermark(UserPrefsRepository.WATERMARK_SEEDS),
                    hiddenMs = prefsRepo.getSyncWatermark(UserPrefsRepository.WATERMARK_HIDDEN)
                )
                val isInitialFullSync = watermarks.likedMs == 0L && watermarks.playlistsMs == 0L
                if (isInitialFullSync) {
                    cloudRepo.purgeExpiredCloudTombstones(user.uid, tombstoneCutoff)
                }

                val snapshot = cloudRepo.fetchAccountSnapshot(watermarks)
                if (auth.currentUser?.uid != user.uid) return@withLock

                val resolvedDisplayName = mergeProfileAndSeedsLocked(user, snapshot, watermarks, now)
                mergeLikedSongsLocked(snapshot, watermarks, now)
                mergePlaylistsLocked(snapshot, watermarks, now)
                mergeHiddenArtistsLocked(snapshot, watermarks, now)

                failedCollections.clear()
                retryAttempt = 0
                val doneAt = System.currentTimeMillis()
                _authState.update {
                    it.copy(
                        displayName = resolvedDisplayName,
                        isSyncing = false,
                        syncStatus = formatUpToDate(doneAt),
                        lastSyncedAtMs = doneAt,
                        errorMessage = null,
                        pendingUnsyncedCount = 0
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Cloud sync failed", e)
                val safeMsg = SecurityValidators.safeUserErrorMessage(e, "Cloud sync failed.")
                failedCollections["merge"] = safeMsg
                _authState.update {
                    it.copy(
                        isSyncing = false,
                        syncStatus = "Sync failed: $safeMsg",
                        errorMessage = safeMsg,
                        pendingUnsyncedCount = failedCollections.size
                    )
                }
                scheduleRetry(user)
            } finally {
                suppressLocalPushCount.decrementAndGet()
            }
        }
    }

    private suspend fun mergeProfileAndSeedsLocked(
        user: FirebaseUser,
        snapshot: CloudAccountSnapshot,
        watermarks: SyncWatermarks,
        now: Long
    ): String {
        val localPrefs = prefsRepo.prefsFlow.first()
        val localTaste = prefsRepo.tasteProfileFlow.first()
        val cloudProfile = snapshot.profile

        val cloudDisplayName = cloudProfile?.displayName?.trim()?.takeIf { it.isNotBlank() && it != "Listener" }
        val userDisplayName = user.displayName?.trim()?.takeIf { it.isNotBlank() }
        val resolvedDisplayName = when {
            localPrefs.userDisplayName.isNotBlank() && localPrefs.userDisplayName != "Listener" ->
                localPrefs.userDisplayName
            cloudDisplayName != null -> cloudDisplayName
            userDisplayName != null -> userDisplayName
            else -> "Listener"
        }

        if (resolvedDisplayName != localPrefs.userDisplayName) {
            prefsRepo.setDisplayName(resolvedDisplayName)
        }

        val shouldAdoptCloudPrefs = cloudProfile != null &&
            !localTaste.onboardingCompleted &&
            cloudProfile.onboardingCompleted

        if (shouldAdoptCloudPrefs && cloudProfile != null) {
            VelunaThemePreset.entries.find { it.id == cloudProfile.themePreset }
                ?.let { prefsRepo.setThemePreset(it) }
            runCatching { ThemeMode.valueOf(cloudProfile.themeMode) }.getOrNull()
                ?.let { prefsRepo.setThemeMode(it) }
            prefsRepo.setCustomAccent(
                cloudProfile.customAccentHex?.trim()?.takeIf { SecurityValidators.isValidHexColor(it) }
            )
            prefsRepo.setAdaptToArtwork(cloudProfile.adaptToArtwork)
            prefsRepo.setCornerRadius(cloudProfile.cornerRadiusDp.toInt().coerceIn(4, 24))
            runCatching { AudioQuality.valueOf(cloudProfile.audioQuality) }.getOrNull()
                ?.let { prefsRepo.setAudioQuality(it) }
            prefsRepo.setAutoplay(cloudProfile.autoplay)
            prefsRepo.setLoudnessNormalization(cloudProfile.loudnessNormalization)
            prefsRepo.setHideMusicVideos(cloudProfile.hideMusicVideos)
        }

        val seedTombstones = dao.getTombstonesByType(SyncEntityTypes.SEED).associateBy { it.entityKey }
        val cloudSeedTombIds = snapshot.tasteSeeds.filter { it.deleted }.map { it.seedId }.toSet()

        val localArtistsById = localTaste.seedArtists.associateBy {
            CloudSyncRepository.resolveSeedDocId("art", it)
        }
        val localSongsById = localTaste.seedSongs.associateBy {
            CloudSyncRepository.resolveSeedDocId("sng", it)
        }

        val localSeedMergeMap = linkedMapOf<String, MergeItem<Pair<String, TasteSeed>>>()
        localArtistsById.forEach { (id, s) ->
            localSeedMergeMap[id] = MergeItem(id, now.coerceAtLeast(1L), false, "ARTIST" to s.copy(id = id))
        }
        localSongsById.forEach { (id, s) ->
            localSeedMergeMap[id] = MergeItem(id, now.coerceAtLeast(1L), false, "SONG" to s.copy(id = id))
        }
        seedTombstones.forEach { (id, t) ->
            localSeedMergeMap[id] = MergeItem(id, t.clientUpdatedAt, true, null)
        }

        val cloudSeedMergeMap = snapshot.tasteSeeds.associate { c ->
            val safeThumb = SecurityValidators.sanitizeHttpsUrl(c.thumbnail, 500)
            val resolvedSeed = if (c.deleted) null else (c.kind.uppercase() to TasteSeed(c.seedId, c.name, c.subtitle, safeThumb))
            val ts = c.clientUpdatedAt.takeIf { it > 0L } ?: (c.updatedAt?.toDate()?.time ?: 1L)
            c.seedId to MergeItem(c.seedId, ts, c.deleted, resolvedSeed)
        }

        val relevantLocalSeeds = if (watermarks.seedsMs == 0L) {
            localSeedMergeMap
        } else {
            localSeedMergeMap.filterKeys { it in cloudSeedMergeMap || it in seedTombstones }
        }

        val seedDecisions = MergeEngine.mergeMaps(relevantLocalSeeds, cloudSeedMergeMap) { a, b ->
            a?.first == b?.first && a?.second?.name == b?.second?.name
        }

        val nextArtistsMap = localArtistsById.toMutableMap()
        val nextSongsMap = localSongsById.toMutableMap()
        var pushedSeedWrites = false
        for (d in seedDecisions) {
            if (d.applyLocally) {
                if (d.deleted) {
                    nextArtistsMap.remove(d.key)
                    nextSongsMap.remove(d.key)
                    dao.upsertTombstone(SyncTombstoneEntity(SyncEntityTypes.SEED, d.key, d.clientUpdatedAt, now))
                } else if (d.value != null) {
                    dao.deleteTombstone(SyncEntityTypes.SEED, d.key)
                    val (kind, seed) = d.value
                    if (kind == "ARTIST") {
                        nextArtistsMap[d.key] = seed.copy(id = d.key)
                    } else {
                        nextSongsMap[d.key] = seed.copy(id = d.key)
                    }
                }
            }
            if (d.pushToCloud || d.pushTombstoneToCloud) {
                pushedSeedWrites = true
            }
        }

        cloudSeedTombIds.forEach { tid ->
            val dec = seedDecisions.find { it.key == tid }
            if (dec?.deleted == true) {
                nextArtistsMap.remove(tid)
                nextSongsMap.remove(tid)
            }
        }

        val mergedArtists = nextArtistsMap.values.distinctBy { it.name.lowercase().trim() }.take(30)
        val mergedSongs = nextSongsMap.values
            .distinctBy { it.name.lowercase().trim() + "::" + (it.subtitle?.lowercase()?.trim().orEmpty()) }
            .take(30)
        val mergedGenres = (localTaste.selectedGenres + (cloudProfile?.selectedGenres ?: emptyList()))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(25)
        val mergedCompleted = localTaste.onboardingCompleted || (cloudProfile?.onboardingCompleted == true)
        val mergedTaste = localTaste.copy(
            onboardingCompleted = mergedCompleted,
            selectedGenres = mergedGenres,
            seedArtists = mergedArtists,
            seedSongs = mergedSongs
        )
        if (mergedTaste != localTaste) {
            prefsRepo.saveTasteProfile(mergedTaste)
        }

        syncedSeedIds.clear()
        syncedSeedIds.addAll(
            mergedArtists.map { CloudSyncRepository.resolveSeedDocId("art", it) } +
                mergedSongs.map { CloudSyncRepository.resolveSeedDocId("sng", it) }
        )

        val latestPrefs = prefsRepo.prefsFlow.first()
        val safePhotoUrl = SecurityValidators.sanitizeHttpsUrl(user.photoUrl?.toString(), 500)
        val currentProfileHash = computeProfileHash(latestPrefs, mergedTaste, resolvedDisplayName, user.email, safePhotoUrl)
        if (cloudProfile == null || prefsRepo.getLastProfilePushHash() != currentProfileHash) {
            cloudRepo.upsertUserProfile(
                prefs = latestPrefs,
                tasteProfile = mergedTaste,
                email = user.email,
                photoUrl = safePhotoUrl,
                overrideDisplayName = resolvedDisplayName
            )
            prefsRepo.setLastProfilePushHash(currentProfileHash)
        }

        val currentSeedsHash = computeSeedsHash(mergedArtists, mergedSongs, seedTombstones.values.toList())
        if (pushedSeedWrites || prefsRepo.getLastSeedsPushHash() != currentSeedsHash) {
            cloudRepo.upsertTasteSeeds(
                artists = mergedArtists,
                songs = mergedSongs,
                tombstones = dao.getTombstonesByType(SyncEntityTypes.SEED),
                nowMs = now
            )
            prefsRepo.setLastSeedsPushHash(currentSeedsHash)
        }
        if (snapshot.maxSeedsUpdatedAtMs > watermarks.seedsMs) {
            prefsRepo.setSyncWatermark(UserPrefsRepository.WATERMARK_SEEDS, snapshot.maxSeedsUpdatedAtMs)
        }
        return resolvedDisplayName
    }

    private suspend fun mergeLikedSongsLocked(
        snapshot: CloudAccountSnapshot,
        watermarks: SyncWatermarks,
        now: Long
    ) {
        val localLikedList = dao.getLikedSongsOnce().filter { !it.videoId.startsWith(LOCAL_SONG_PREFIX) }
        val localLikedById = localLikedList.associateBy { it.videoId }
        val likedTombstones = dao.getTombstonesByType(SyncEntityTypes.LIKED).associateBy { it.entityKey }

        val localLikedMerge = linkedMapOf<String, MergeItem<SongItem>>()
        localLikedById.forEach { (id, entity) ->
            repository.decodeSong(entity.songJson)?.let { song ->
                localLikedMerge[id] = MergeItem(id, entity.likedAt, false, song)
            }
        }
        likedTombstones.forEach { (id, tomb) ->
            val existingLocal = localLikedMerge[id]
            if (existingLocal == null || tomb.clientUpdatedAt >= existingLocal.clientUpdatedAt) {
                localLikedMerge[id] = MergeItem(id, tomb.clientUpdatedAt, true, null)
            }
        }

        val cloudLikedMerge = snapshot.likedSongs.filter { it.videoId.isNotBlank() && !it.videoId.startsWith(LOCAL_SONG_PREFIX) }
            .associate { c ->
                val ts = c.clientUpdatedAt.takeIf { it > 0L } ?: c.likedAt.takeIf { it > 0L } ?: (c.updatedAt?.toDate()?.time ?: 1L)
                c.videoId to MergeItem(c.videoId, ts, c.deleted, if (c.deleted) null else c.toSongItem())
            }

        val effectiveLocalLiked = if (watermarks.likedMs == 0L) {
            localLikedMerge
        } else {
            localLikedMerge.filterKeys { it in cloudLikedMerge || it in likedTombstones }
        }

        val likedDecisions = MergeEngine.mergeMaps(effectiveLocalLiked, cloudLikedMerge) { a, b ->
            a?.videoId == b?.videoId && a?.title == b?.title
        }

        for (d in likedDecisions) {
            if (d.applyLocally) {
                if (d.deleted) {
                    dao.deleteLikedSong(d.key)
                    dao.upsertTombstone(SyncTombstoneEntity(SyncEntityTypes.LIKED, d.key, d.clientUpdatedAt, now))
                    syncedLikedIds.remove(d.key)
                } else if (d.value != null) {
                    dao.deleteTombstone(SyncEntityTypes.LIKED, d.key)
                    dao.insertLikedSong(
                        LikedSongEntity(
                            videoId = d.key,
                            songJson = repository.encodeSong(d.value),
                            likedAt = d.clientUpdatedAt.takeIf { it > 0L } ?: now
                        )
                    )
                    syncedLikedIds.add(d.key)
                }
            } else if (d.pushToCloud && d.value != null) {
                cloudRepo.syncLikedSong(d.value, liked = true, likedAtMs = d.clientUpdatedAt, clientUpdatedAtMs = d.clientUpdatedAt)
                syncedLikedIds.add(d.key)
            } else if (d.pushTombstoneToCloud) {
                cloudRepo.syncLikedSong(SongItem(videoId = d.key, title = "", artists = ""), liked = false, clientUpdatedAtMs = d.clientUpdatedAt)
                syncedLikedIds.remove(d.key)
            }
        }
        if (watermarks.likedMs == 0L) {
            syncedLikedIds.clear()
            syncedLikedIds.addAll(dao.getLikedSongsOnce().map { it.videoId }.filter { !it.startsWith(LOCAL_SONG_PREFIX) })
        }
        if (snapshot.maxLikedUpdatedAtMs > watermarks.likedMs) {
            prefsRepo.setSyncWatermark(UserPrefsRepository.WATERMARK_LIKED, snapshot.maxLikedUpdatedAtMs)
        }
    }

    private suspend fun mergePlaylistsLocked(
        snapshot: CloudAccountSnapshot,
        watermarks: SyncWatermarks,
        now: Long
    ) {
        val localPlaylists = dao.getPlaylistsOnce()
        val plTombstones = dao.getTombstonesByType(SyncEntityTypes.PLAYLIST).associateBy { it.entityKey }
        val localByKey = mutableMapOf<String, LocalPlaylistEntity>()
        val localByTitle = mutableMapOf<String, LocalPlaylistEntity>()
        localPlaylists.forEach { pl ->
            val key = localPlaylistToCloudKey[pl.id] ?: "pl_local_${pl.id}"
            localPlaylistToCloudKey[pl.id] = key
            localByKey[key] = pl
            localByTitle[pl.title.lowercase().trim()] = pl
        }

        val matchedLocalIds = mutableSetOf<Long>()
        val nextPlaylistSignatures = syncedPlaylistTimestamps.toMutableMap()

        for (cloudBundle in snapshot.playlists) {
            val cloudPl = cloudBundle.playlist
            val matchedLocal = localByKey[cloudPl.playlistKey]
                ?: localByTitle[cloudPl.title.lowercase().trim()]
            val localTomb = plTombstones[cloudPl.playlistKey]

            if (cloudPl.deleted) {
                val cloudTs = cloudPl.clientUpdatedAt
                if (matchedLocal != null && (cloudTs >= matchedLocal.updatedAt)) {
                    dao.clearPlaylistTracks(matchedLocal.id)
                    dao.deletePlaylist(matchedLocal.id)
                    matchedLocalIds.add(matchedLocal.id)
                    dao.upsertTombstone(SyncTombstoneEntity(SyncEntityTypes.PLAYLIST, cloudPl.playlistKey, cloudTs, now))
                    nextPlaylistSignatures.remove(cloudPl.playlistKey)
                } else if (matchedLocal == null) {
                    dao.upsertTombstone(SyncTombstoneEntity(SyncEntityTypes.PLAYLIST, cloudPl.playlistKey, cloudTs, now))
                    nextPlaylistSignatures.remove(cloudPl.playlistKey)
                }
                continue
            }

            if (localTomb != null && localTomb.clientUpdatedAt >= cloudPl.clientUpdatedAt) {
                cloudRepo.deletePlaylist(cloudPl.playlistKey, localTomb.clientUpdatedAt, clearLegacyTracksArray = cloudBundle.hasLegacyArrayTracks)
                nextPlaylistSignatures.remove(cloudPl.playlistKey)
                continue
            }

            val remoteTracks: List<SongItem> = if (cloudBundle.subcollectionTracks.isNotEmpty() || !cloudBundle.hasLegacyArrayTracks) {
                cloudBundle.subcollectionTracks
                    .filter { !it.deleted }
                    .sortedBy { it.position }
                    .mapNotNull { it.toSongItem() }
            } else {
                cloudPl.tracks.mapNotNull { CloudSyncRepository.songFromCloudMap(it) }
            }

            val safeCloudCoverUri = SecurityValidators.sanitizeHttpsUrl(cloudPl.coverUri, 500)
            if (matchedLocal != null) {
                matchedLocalIds.add(matchedLocal.id)
                localPlaylistToCloudKey[matchedLocal.id] = cloudPl.playlistKey
                val localTracks = dao.getPlaylistTracks(matchedLocal.id)
                    .mapNotNull { repository.decodeSong(it.songJson) }
                    .filter { !it.isLocal }

                if (cloudPl.clientUpdatedAt > matchedLocal.updatedAt) {
                    dao.updatePlaylist(
                        id = matchedLocal.id,
                        title = cloudPl.title.ifBlank { matchedLocal.title },
                        description = cloudPl.description,
                        coverUri = safeCloudCoverUri ?: matchedLocal.coverUri,
                        updatedAt = cloudPl.clientUpdatedAt
                    )
                    dao.clearPlaylistTracks(matchedLocal.id)
                    remoteTracks.forEachIndexed { idx, s ->
                        dao.insertPlaylistTrack(
                            LocalPlaylistTrackEntity(
                                playlistId = matchedLocal.id,
                                videoId = s.videoId,
                                songJson = repository.encodeSong(s),
                                position = idx
                            )
                        )
                    }
                    if (cloudBundle.hasLegacyArrayTracks) {
                        cloudRepo.upsertPlaylist(
                            playlistKey = cloudPl.playlistKey,
                            title = cloudPl.title.ifBlank { matchedLocal.title },
                            description = cloudPl.description,
                            coverUri = safeCloudCoverUri ?: matchedLocal.coverUri,
                            tracks = remoteTracks,
                            clientUpdatedAt = cloudPl.clientUpdatedAt,
                            removeLegacyTracksField = true
                        )
                    }
                    nextPlaylistSignatures[cloudPl.playlistKey] = playlistSig(
                        cloudPl.title, cloudPl.description, remoteTracks.map { it.videoId }
                    )
                } else if (matchedLocal.updatedAt > cloudPl.clientUpdatedAt || cloudBundle.hasLegacyArrayTracks) {
                    cloudRepo.upsertPlaylist(
                        playlistKey = cloudPl.playlistKey,
                        title = matchedLocal.title,
                        description = matchedLocal.description,
                        coverUri = matchedLocal.coverUri,
                        tracks = localTracks,
                        clientUpdatedAt = maxOf(matchedLocal.updatedAt, cloudPl.clientUpdatedAt),
                        removeLegacyTracksField = cloudBundle.hasLegacyArrayTracks
                    )
                    nextPlaylistSignatures[cloudPl.playlistKey] = playlistSig(
                        matchedLocal.title, matchedLocal.description, localTracks.map { it.videoId }
                    )
                } else {
                    nextPlaylistSignatures[cloudPl.playlistKey] = playlistSig(
                        matchedLocal.title, matchedLocal.description, localTracks.map { it.videoId }
                    )
                }
            } else {
                dao.deleteTombstone(SyncEntityTypes.PLAYLIST, cloudPl.playlistKey)
                val newLocalId = dao.insertPlaylist(
                    LocalPlaylistEntity(
                        title = cloudPl.title.ifBlank { "Playlist" },
                        description = cloudPl.description,
                        coverUri = safeCloudCoverUri,
                        createdAt = now,
                        updatedAt = cloudPl.clientUpdatedAt.takeIf { it > 0L } ?: now
                    )
                )
                localPlaylistToCloudKey[newLocalId] = cloudPl.playlistKey
                remoteTracks.forEachIndexed { idx, s ->
                    dao.insertPlaylistTrack(
                        LocalPlaylistTrackEntity(
                            playlistId = newLocalId,
                            videoId = s.videoId,
                            songJson = repository.encodeSong(s),
                            position = idx
                        )
                    )
                }
                if (cloudBundle.hasLegacyArrayTracks) {
                    cloudRepo.upsertPlaylist(
                        playlistKey = cloudPl.playlistKey,
                        title = cloudPl.title.ifBlank { "Playlist" },
                        description = cloudPl.description,
                        coverUri = safeCloudCoverUri,
                        tracks = remoteTracks,
                        clientUpdatedAt = cloudPl.clientUpdatedAt.takeIf { it > 0L } ?: now,
                        removeLegacyTracksField = true
                    )
                }
                nextPlaylistSignatures[cloudPl.playlistKey] = playlistSig(
                    cloudPl.title, cloudPl.description, remoteTracks.map { it.videoId }
                )
            }
        }

        if (watermarks.playlistsMs == 0L) {
            val seenCloudKeys = snapshot.playlists.map { it.playlist.playlistKey }.toSet()
            for (localPl in localPlaylists) {
                if (localPl.id !in matchedLocalIds) {
                    val key = localPlaylistToCloudKey[localPl.id] ?: "pl_local_${localPl.id}"
                    localPlaylistToCloudKey[localPl.id] = key
                    val tracks = dao.getPlaylistTracks(localPl.id)
                        .mapNotNull { repository.decodeSong(it.songJson) }
                        .filter { !it.isLocal }
                    cloudRepo.upsertPlaylist(
                        playlistKey = key,
                        title = localPl.title,
                        description = localPl.description,
                        coverUri = localPl.coverUri,
                        tracks = tracks,
                        clientUpdatedAt = localPl.updatedAt
                    )
                    nextPlaylistSignatures[key] = playlistSig(
                        localPl.title, localPl.description, tracks.map { it.videoId }
                    )
                }
            }
            for ((key, tomb) in plTombstones) {
                if (key !in seenCloudKeys) {
                    cloudRepo.deletePlaylist(key, tomb.clientUpdatedAt, clearLegacyTracksArray = false)
                }
            }
        }

        syncedPlaylistTimestamps.clear()
        syncedPlaylistTimestamps.putAll(nextPlaylistSignatures)
        if (snapshot.maxPlaylistsUpdatedAtMs > watermarks.playlistsMs) {
            prefsRepo.setSyncWatermark(UserPrefsRepository.WATERMARK_PLAYLISTS, snapshot.maxPlaylistsUpdatedAtMs)
        }
    }

    private suspend fun mergeHiddenArtistsLocked(
        snapshot: CloudAccountSnapshot,
        watermarks: SyncWatermarks,
        now: Long
    ) {
        val localBlocked = dao.getBlockedArtistsOnce()
        val localBlockedMap = localBlocked.associateBy { it.name.lowercase().trim() }
        val hiddenTombstones = dao.getTombstonesByType(SyncEntityTypes.HIDDEN_ARTIST).associateBy { it.entityKey }

        val localHiddenMerge = linkedMapOf<String, MergeItem<BlockedArtistEntity>>()
        localBlockedMap.forEach { (norm, entity) ->
            if (norm.isNotBlank()) {
                localHiddenMerge[norm] = MergeItem(norm, entity.blockedAt, false, entity)
            }
        }
        hiddenTombstones.forEach { (norm, tomb) ->
            val existing = localHiddenMerge[norm]
            if (existing == null || tomb.clientUpdatedAt >= existing.clientUpdatedAt) {
                localHiddenMerge[norm] = MergeItem(norm, tomb.clientUpdatedAt, true, null)
            }
        }

        val cloudHiddenMerge = snapshot.hiddenArtists
            .filter { it.name.isNotBlank() || it.deleted }
            .associate { c ->
                val normKey = c.name.lowercase().trim().ifBlank { c.artistKey }
                val ts = c.clientUpdatedAt.takeIf { it > 0L } ?: c.blockedAt.takeIf { it > 0L } ?: (c.updatedAt?.toDate()?.time ?: 1L)
                normKey to MergeItem(
                    key = normKey,
                    clientUpdatedAt = ts,
                    deleted = c.deleted,
                    value = if (c.deleted) null else BlockedArtistEntity(c.name, c.channelId, c.blockedAt.takeIf { it > 0L } ?: ts)
                )
            }

        val effectiveLocalHidden = if (watermarks.hiddenMs == 0L) {
            localHiddenMerge
        } else {
            localHiddenMerge.filterKeys { it in cloudHiddenMerge || it in hiddenTombstones }
        }

        val hiddenDecisions = MergeEngine.mergeMaps(effectiveLocalHidden, cloudHiddenMerge) { a, b ->
            a?.name.equals(b?.name, ignoreCase = true)
        }

        for (d in hiddenDecisions) {
            if (d.applyLocally) {
                if (d.deleted) {
                    localBlockedMap[d.key]?.let { dao.deleteBlockedArtist(it.name) }
                    dao.upsertTombstone(SyncTombstoneEntity(SyncEntityTypes.HIDDEN_ARTIST, d.key, d.clientUpdatedAt, now))
                    syncedHiddenNames.remove(d.key)
                } else if (d.value != null) {
                    dao.deleteTombstone(SyncEntityTypes.HIDDEN_ARTIST, d.key)
                    dao.insertBlockedArtist(d.value)
                    syncedHiddenNames.add(d.key)
                }
            } else if (d.pushToCloud && d.value != null) {
                cloudRepo.syncHiddenArtist(d.value.name, d.value.channelId, blocked = true, blockedAtMs = d.value.blockedAt, clientUpdatedAtMs = d.clientUpdatedAt)
                syncedHiddenNames.add(d.key)
            } else if (d.pushTombstoneToCloud) {
                cloudRepo.syncHiddenArtist(d.key, null, blocked = false, clientUpdatedAtMs = d.clientUpdatedAt)
                syncedHiddenNames.remove(d.key)
            }
        }
        if (watermarks.hiddenMs == 0L) {
            syncedHiddenNames.clear()
            syncedHiddenNames.addAll(dao.getBlockedArtistsOnce().map { it.name.lowercase().trim() }.filter { it.isNotBlank() })
        }
        if (snapshot.maxHiddenUpdatedAtMs > watermarks.hiddenMs) {
            prefsRepo.setSyncWatermark(UserPrefsRepository.WATERMARK_HIDDEN, snapshot.maxHiddenUpdatedAtMs)
        }
    }

    @Suppress("OPT_IN_USAGE")
    private fun launchWatchersLocked(user: FirebaseUser) {
        val uid = user.uid

        // Watch Preferences & TasteProfile
        val prefsJob = scope.launch {
            var initialized = false
            combine(prefsRepo.prefsFlow, prefsRepo.tasteProfileFlow) { prefs, taste -> prefs to taste }
                .debounce(800L)
                .collectLatest { (prefs, taste) ->
                    if (auth.currentUser?.uid != uid || suppressLocalPushCount.get() > 0) return@collectLatest
                    if (!initialized) {
                        initialized = true
                        return@collectLatest
                    }

                    val safePhotoUrl = SecurityValidators.sanitizeHttpsUrl(user.photoUrl?.toString(), 500)
                    val profileHash = computeProfileHash(prefs, taste, prefs.userDisplayName, user.email, safePhotoUrl)
                    val lastProfHash = prefsRepo.getLastProfilePushHash()

                    val now = System.currentTimeMillis()
                    val currentSeedIds = (
                        taste.seedArtists.map { CloudSyncRepository.resolveSeedDocId("art", it) } +
                            taste.seedSongs.map { CloudSyncRepository.resolveSeedDocId("sng", it) }
                        ).toSet()
                    val removedSeedIds = syncedSeedIds - currentSeedIds
                    removedSeedIds.forEach { removedId ->
                        dao.upsertTombstone(SyncTombstoneEntity(SyncEntityTypes.SEED, removedId, now, now))
                    }
                    currentSeedIds.forEach { id ->
                        dao.deleteTombstone(SyncEntityTypes.SEED, id)
                    }
                    val tombstones = dao.getTombstonesByType(SyncEntityTypes.SEED)
                    val seedsHash = computeSeedsHash(taste.seedArtists, taste.seedSongs, tombstones)
                    val lastSeedsHash = prefsRepo.getLastSeedsPushHash()

                    if (profileHash == lastProfHash && seedsHash == lastSeedsHash) return@collectLatest

                    _authState.update { it.copy(isSyncing = true, syncStatus = "Syncing…") }
                    val res = suspendRunCatching {
                        if (profileHash != lastProfHash) {
                            cloudRepo.upsertUserProfile(
                                prefs = prefs,
                                tasteProfile = taste,
                                email = user.email,
                                photoUrl = safePhotoUrl
                            )
                            prefsRepo.setLastProfilePushHash(profileHash)
                        }
                        if (seedsHash != lastSeedsHash) {
                            cloudRepo.upsertTasteSeeds(taste.seedArtists, taste.seedSongs, tombstones, now)
                            syncedSeedIds.clear()
                            syncedSeedIds.addAll(currentSeedIds)
                            prefsRepo.setLastSeedsPushHash(seedsHash)
                        }
                    }
                    recordWatcherResult("profile", res, user)
                }
        }
        watcherJobs.add(prefsJob)

        // Watch Liked Songs
        val likesJob = scope.launch {
            dao.observeLikedSongs().collectLatest { rows ->
                if (auth.currentUser?.uid != uid || suppressLocalPushCount.get() > 0) return@collectLatest
                syncMutex.withLock {
                    if (auth.currentUser?.uid != uid || suppressLocalPushCount.get() > 0) return@withLock
                    val currentMap = rows.filter { !it.videoId.startsWith(LOCAL_SONG_PREFIX) }.associateBy { it.videoId }
                    val addedOrUpdated = currentMap.keys - syncedLikedIds
                    val removed = syncedLikedIds - currentMap.keys
                    if (addedOrUpdated.isEmpty() && removed.isEmpty()) return@withLock

                    _authState.update { it.copy(isSyncing = true, syncStatus = "Syncing…") }
                    val now = System.currentTimeMillis()
                    val res = suspendRunCatching {
                        for (id in addedOrUpdated) {
                            val entity = currentMap[id] ?: continue
                            val song = repository.decodeSong(entity.songJson) ?: continue
                            dao.deleteTombstone(SyncEntityTypes.LIKED, id)
                            cloudRepo.syncLikedSong(song, liked = true, likedAtMs = entity.likedAt, clientUpdatedAtMs = entity.likedAt)
                            syncedLikedIds.add(id)
                        }
                        for (id in removed) {
                            dao.upsertTombstone(SyncTombstoneEntity(SyncEntityTypes.LIKED, id, now, now))
                            val dummy = SongItem(videoId = id, title = "", artists = "")
                            cloudRepo.syncLikedSong(dummy, liked = false, clientUpdatedAtMs = now)
                            syncedLikedIds.remove(id)
                        }
                    }
                    recordWatcherResult("likes", res, user)
                }
            }
        }
        watcherJobs.add(likesJob)

        // Watch Playlists + Track Counts
        val playlistsJob = scope.launch {
            combine(dao.observePlaylists(), dao.observePlaylistTrackCounts()) { pls, _ -> pls }
                .debounce(500L)
                .collectLatest { playlists ->
                    if (auth.currentUser?.uid != uid || suppressLocalPushCount.get() > 0) return@collectLatest
                    syncMutex.withLock {
                        if (auth.currentUser?.uid != uid || suppressLocalPushCount.get() > 0) return@withLock
                        val activeKeys = mutableSetOf<String>()
                        val dirtyPlaylists = mutableListOf<Triple<String, LocalPlaylistEntity, List<SongItem>>>()

                        for (pl in playlists) {
                            val key = localPlaylistToCloudKey[pl.id] ?: "pl_local_${pl.id}".also {
                                localPlaylistToCloudKey[pl.id] = it
                            }
                            activeKeys.add(key)
                            val tracks = dao.getPlaylistTracks(pl.id)
                                .mapNotNull { repository.decodeSong(it.songJson) }
                                .filter { !it.isLocal }
                            val sig = playlistSig(pl.title, pl.description, tracks.map { it.videoId })
                            if (syncedPlaylistTimestamps[key] != sig) {
                                dirtyPlaylists.add(Triple(key, pl, tracks))
                            }
                        }
                        val deletedKeys = syncedPlaylistTimestamps.keys - activeKeys
                        if (dirtyPlaylists.isEmpty() && deletedKeys.isEmpty()) return@withLock

                        _authState.update { it.copy(isSyncing = true, syncStatus = "Syncing…") }
                        val now = System.currentTimeMillis()
                        val res = suspendRunCatching {
                            for ((key, pl, tracks) in dirtyPlaylists) {
                                dao.deleteTombstone(SyncEntityTypes.PLAYLIST, key)
                                cloudRepo.upsertPlaylist(
                                    playlistKey = key,
                                    title = pl.title,
                                    description = pl.description,
                                    coverUri = pl.coverUri,
                                    tracks = tracks,
                                    clientUpdatedAt = pl.updatedAt
                                )
                                syncedPlaylistTimestamps[key] = playlistSig(pl.title, pl.description, tracks.map { it.videoId })
                            }
                            for (delKey in deletedKeys) {
                                dao.upsertTombstone(SyncTombstoneEntity(SyncEntityTypes.PLAYLIST, delKey, now, now))
                                cloudRepo.deletePlaylist(delKey, clientUpdatedAt = now)
                                syncedPlaylistTimestamps.remove(delKey)
                            }
                        }
                        recordWatcherResult("playlists", res, user)
                    }
                }
        }
        watcherJobs.add(playlistsJob)

        // Watch Hidden Artists
        val hiddenJob = scope.launch {
            dao.observeBlockedArtists().collectLatest { rows ->
                if (auth.currentUser?.uid != uid || suppressLocalPushCount.get() > 0) return@collectLatest
                syncMutex.withLock {
                    if (auth.currentUser?.uid != uid || suppressLocalPushCount.get() > 0) return@withLock
                    val byNorm = rows.associateBy { it.name.lowercase().trim() }
                    val added = byNorm.keys - syncedHiddenNames
                    val removed = syncedHiddenNames - byNorm.keys
                    if (added.isEmpty() && removed.isEmpty()) return@withLock

                    _authState.update { it.copy(isSyncing = true, syncStatus = "Syncing…") }
                    val now = System.currentTimeMillis()
                    val res = suspendRunCatching {
                        for (norm in added) {
                            val row = byNorm[norm] ?: continue
                            dao.deleteTombstone(SyncEntityTypes.HIDDEN_ARTIST, norm)
                            cloudRepo.syncHiddenArtist(row.name, row.channelId, blocked = true, blockedAtMs = row.blockedAt, clientUpdatedAtMs = row.blockedAt)
                            syncedHiddenNames.add(norm)
                        }
                        for (norm in removed) {
                            dao.upsertTombstone(SyncTombstoneEntity(SyncEntityTypes.HIDDEN_ARTIST, norm, now, now))
                            cloudRepo.syncHiddenArtist(norm, null, blocked = false, clientUpdatedAtMs = now)
                            syncedHiddenNames.remove(norm)
                        }
                    }
                    recordWatcherResult("hidden", res, user)
                }
            }
        }
        watcherJobs.add(hiddenJob)
    }

    private fun recordWatcherResult(collection: String, result: Result<Unit>, user: FirebaseUser) {
        result.onSuccess {
            failedCollections.remove(collection)
            if (failedCollections.isEmpty()) {
                retryAttempt = 0
                val now = System.currentTimeMillis()
                _authState.update {
                    it.copy(
                        isSyncing = false,
                        lastSyncedAtMs = now,
                        syncStatus = formatUpToDate(now),
                        errorMessage = null,
                        pendingUnsyncedCount = 0
                    )
                }
            } else {
                val firstErr = failedCollections.values.first()
                _authState.update {
                    it.copy(
                        isSyncing = false,
                        syncStatus = "Sync failed: $firstErr",
                        errorMessage = firstErr,
                        pendingUnsyncedCount = failedCollections.size
                    )
                }
            }
        }.onFailure { err ->
            if (err is CancellationException) throw err
            val msg = SecurityValidators.safeUserErrorMessage(err, "Cloud sync error")
            failedCollections[collection] = msg
            if (BuildConfig.DEBUG) Log.w(TAG, "Watcher [$collection] sync failed: $msg")
            _authState.update {
                it.copy(
                    isSyncing = false,
                    syncStatus = "Sync failed: $msg",
                    errorMessage = msg,
                    pendingUnsyncedCount = failedCollections.size
                )
            }
            scheduleRetry(user)
        }
    }

    private fun scheduleRetry(user: FirebaseUser) {
        synchronized(syncLifecycleLock) {
            if (activeUserId != user.uid || retryJob?.isActive == true) return
            val backoffMs = (5_000L * (1L shl retryAttempt.coerceAtMost(3))).coerceAtMost(60_000L)
            retryAttempt++
            retryJob = scope.launch {
                delay(backoffMs)
                if (auth.currentUser?.uid == user.uid && failedCollections.isNotEmpty()) {
                    runBidirectionalMerge(user)
                }
            }
        }
    }

    private fun formatUpToDate(epochMs: Long): String {
        val fmt = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
        return "Up to date at ${fmt.format(Instant.ofEpochMilli(epochMs))}"
    }

    private fun playlistSig(title: String, description: String, trackIds: List<String>): String =
        "$title|${description.hashCode()}|${trackIds.joinToString(",")}"

    private fun computeProfileHash(
        prefs: VelunaUserPrefs,
        taste: TasteProfile,
        displayName: String,
        email: String?,
        photoUrl: String?
    ): String {
        val raw = listOf(
            displayName.trim(),
            email.orEmpty(),
            photoUrl.orEmpty(),
            prefs.themePreset.id,
            prefs.themeMode.name,
            prefs.customAccentHex.orEmpty(),
            prefs.adaptToArtwork.toString(),
            prefs.cornerRadiusDp.toString(),
            prefs.audioQuality.name,
            prefs.autoplay.toString(),
            prefs.loudnessNormalization.toString(),
            prefs.hideMusicVideos.toString(),
            taste.onboardingCompleted.toString(),
            taste.selectedGenres.joinToString(",")
        ).joinToString("|")
        return sha256Hex(raw)
    }

    private fun computeSeedsHash(
        artists: List<TasteSeed>,
        songs: List<TasteSeed>,
        tombstones: List<SyncTombstoneEntity>
    ): String {
        val raw = buildString {
            append(artists.sortedBy { it.name }.joinToString(",") { "${CloudSyncRepository.resolveSeedDocId("art", it)}:${it.name}" })
            append("|")
            append(songs.sortedBy { it.name }.joinToString(",") { "${CloudSyncRepository.resolveSeedDocId("sng", it)}:${it.name}" })
            append("|")
            append(tombstones.sortedBy { it.entityKey }.joinToString(",") { "${it.entityKey}@${it.clientUpdatedAt}" })
        }
        return sha256Hex(raw)
    }

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "CloudSyncCoordinator"
        private const val TOMBSTONE_RETENTION_MS = 30L * 24L * 3600L * 1000L
    }
}
