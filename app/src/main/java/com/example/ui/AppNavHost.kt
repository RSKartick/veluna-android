package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.example.VelunaAppContainer
import com.example.data.cloud.CloudAuthUiState
import com.example.data.local.VelunaUserPrefs
import com.example.model.BrowseItem
import com.example.model.DownloadProgress
import com.example.model.MoodTile
import com.example.model.SongItem
import com.example.model.TasteProfile
import com.example.playback.PlaybackUiState
import com.example.ui.navigation.AlbumRoute
import com.example.ui.navigation.ArtistRoute
import com.example.ui.navigation.HistoryRoute
import com.example.ui.navigation.HomeRoute
import com.example.ui.navigation.PlaylistRoute
import com.example.ui.navigation.VelunaTopTab
import com.example.ui.screens.AlbumDetailScreen
import com.example.ui.screens.ArtistDetailScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.PlaylistDetailScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StatsScreen
import com.example.ui.theme.VelunaMotion
import com.example.ui.viewmodel.DetailViewModel
import com.example.ui.viewmodel.HomeSearchViewModel
import com.example.ui.viewmodel.LibraryStatsViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun VelunaNavHost(
    navController: NavHostController,
    selectedTopTab: VelunaTopTab,
    visitedTabMask: Int,
    isAtTopTabRoot: Boolean,
    container: VelunaAppContainer,
    homeSearchVm: HomeSearchViewModel,
    detailVm: DetailViewModel,
    libraryStatsVm: LibraryStatsViewModel,
    prefs: VelunaUserPrefs,
    playbackFlow: StateFlow<PlaybackUiState>,
    playlists: List<BrowseItem>,
    likedIds: Set<String>,
    downloadedTracks: List<SongItem>,
    downloadsFlow: StateFlow<Map<String, DownloadProgress>>,
    tasteProfile: TasteProfile?,
    cloudAuthState: CloudAuthUiState,
    ensureNotificationPermission: () -> Unit,
    onNavigateTopTab: (VelunaTopTab) -> Unit,
    onPlayTracks: (List<SongItem>, Int, String) -> Unit,
    onOpenBrowseItem: (BrowseItem) -> Unit,
    onTrackMore: (SongItem) -> Unit,
    onOpenEditHome: () -> Unit,
    onOpenCreatePlaylist: () -> Unit,
    onOpenImportPlaylist: () -> Unit,
    onOpenTasteOnboarding: () -> Unit,
    onOpenEqualizer: () -> Unit,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        enterTransition = {
            slideInHorizontally(
                animationSpec = VelunaMotion.expressiveIntOffsetSpring(),
                initialOffsetX = { (it * 0.08f).toInt() }
            ) + fadeIn(VelunaMotion.fastFade())
        },
        exitTransition = {
            slideOutHorizontally(
                animationSpec = VelunaMotion.snappyIntOffsetSpring(),
                targetOffsetX = { -(it * 0.05f).toInt() }
            ) + fadeOut(VelunaMotion.fastFade())
        },
        popEnterTransition = {
            slideInHorizontally(
                animationSpec = VelunaMotion.expressiveIntOffsetSpring(),
                initialOffsetX = { -(it * 0.08f).toInt() }
            ) + fadeIn(VelunaMotion.fastFade())
        },
        popExitTransition = {
            slideOutHorizontally(
                animationSpec = VelunaMotion.snappyIntOffsetSpring(),
                targetOffsetX = { (it * 0.08f).toInt() }
            ) + fadeOut(VelunaMotion.fastFade())
        },
        modifier = modifier
    ) {
        composable<HomeRoute> {
            TopLevelTabDeck(
                selectedTopTab = selectedTopTab,
                visitedTabMask = visitedTabMask,
                isAtTopTabRoot = isAtTopTabRoot,
                navController = navController,
                container = container,
                homeSearchVm = homeSearchVm,
                libraryStatsVm = libraryStatsVm,
                prefs = prefs,
                playbackFlow = playbackFlow,
                playlists = playlists,
                likedIds = likedIds,
                downloadedTracks = downloadedTracks,
                downloadsFlow = downloadsFlow,
                tasteProfile = tasteProfile,
                cloudAuthState = cloudAuthState,
                ensureNotificationPermission = ensureNotificationPermission,
                onNavigateTopTab = onNavigateTopTab,
                onPlayTracks = onPlayTracks,
                onOpenBrowseItem = onOpenBrowseItem,
                onTrackMore = onTrackMore,
                onOpenEditHome = onOpenEditHome,
                onOpenCreatePlaylist = onOpenCreatePlaylist,
                onOpenImportPlaylist = onOpenImportPlaylist,
                onOpenTasteOnboarding = onOpenTasteOnboarding,
                onOpenEqualizer = onOpenEqualizer
            )
        }

        composable<AlbumRoute> { entry ->
            val route = entry.toRoute<AlbumRoute>()
            LaunchedEffect(route.albumId) { detailVm.loadAlbum(route.albumId) }
            val albumState by detailVm.albumState.collectAsStateWithLifecycle()
            val isSaved by detailVm.observeIsAlbumSaved(route.albumId).collectAsStateWithLifecycle()
            val nowPlayingId by remember(playbackFlow) {
                playbackFlow.map { it.nowPlaying?.videoId }.distinctUntilChanged()
            }.collectAsStateWithLifecycle(initialValue = null)

            AlbumDetailScreen(
                state = albumState,
                isSaved = isSaved,
                nowPlayingId = nowPlayingId,
                onBack = { navController.popBackStack() },
                onToggleSave = { detailVm.toggleSaveAlbum(isSaved) },
                onPinShortcut = detailVm::pinAsShortcut,
                onPlayTracks = onPlayTracks,
                onTrackMore = onTrackMore,
                onOpenBrowseItem = onOpenBrowseItem,
                onOpenArtist = { id -> navController.navigate(ArtistRoute(id)) }
            )
        }

        composable<ArtistRoute> { entry ->
            val route = entry.toRoute<ArtistRoute>()
            LaunchedEffect(route.channelId) { detailVm.loadArtist(route.channelId) }
            val artistState by detailVm.artistState.collectAsStateWithLifecycle()
            val isFollowed by detailVm.observeIsArtistFollowed(route.channelId).collectAsStateWithLifecycle()
            val nowPlayingId by remember(playbackFlow) {
                playbackFlow.map { it.nowPlaying?.videoId }.distinctUntilChanged()
            }.collectAsStateWithLifecycle(initialValue = null)

            ArtistDetailScreen(
                state = artistState,
                isFollowed = isFollowed,
                nowPlayingId = nowPlayingId,
                onBack = { navController.popBackStack() },
                onToggleFollow = { detailVm.toggleFollowArtist(isFollowed) },
                onPinShortcut = detailVm::pinAsShortcut,
                onPlayTracks = onPlayTracks,
                onTrackMore = onTrackMore,
                onOpenBrowseItem = onOpenBrowseItem
            )
        }

        composable<PlaylistRoute> { entry ->
            val route = entry.toRoute<PlaylistRoute>()
            LaunchedEffect(route.playlistId) { detailVm.loadPlaylist(route.playlistId, force = true) }
            val playlistState by detailVm.playlistState.collectAsStateWithLifecycle()
            val nowPlayingId by remember(playbackFlow) {
                playbackFlow.map { it.nowPlaying?.videoId }.distinctUntilChanged()
            }.collectAsStateWithLifecycle(initialValue = null)

            PlaylistDetailScreen(
                state = playlistState,
                nowPlayingId = nowPlayingId,
                onBack = { navController.popBackStack() },
                onFilterChange = detailVm::setPlaylistFilter,
                onSortChange = detailVm::setPlaylistSort,
                onPlayTracks = onPlayTracks,
                onTrackMore = onTrackMore,
                onEditLocalPlaylist = detailVm::updateLocalPlaylistDetails,
                onRemoveDuplicates = { id ->
                    detailVm.removeDuplicatesFromLocalPlaylist(id) { removed ->
                        libraryStatsVm.postStatus("Removed $removed duplicate track(s)")
                    }
                },
                onDeletePlaylist = { id ->
                    detailVm.deleteLocalPlaylist(id) { navController.popBackStack() }
                },
                onExportM3u = detailVm::exportM3uText,
                onExportCsv = detailVm::exportCsvText,
                onPinShortcut = detailVm::pinAsShortcut,
                onStatusMessage = libraryStatsVm::postStatus
            )
        }

        composable<HistoryRoute> {
            val groups by libraryStatsVm.historyGroups.collectAsStateWithLifecycle()
            HistoryScreen(
                groups = groups,
                onBack = { navController.popBackStack() },
                onPlaySong = { song ->
                    ensureNotificationPermission()
                    container.playerController.playSong(song, "Listening History")
                },
                onTrackMore = onTrackMore
            )
        }
    }
}

@Composable
private fun TopLevelTabDeck(
    selectedTopTab: VelunaTopTab,
    visitedTabMask: Int,
    isAtTopTabRoot: Boolean,
    navController: NavHostController,
    container: VelunaAppContainer,
    homeSearchVm: HomeSearchViewModel,
    libraryStatsVm: LibraryStatsViewModel,
    prefs: VelunaUserPrefs,
    playbackFlow: StateFlow<PlaybackUiState>,
    playlists: List<BrowseItem>,
    likedIds: Set<String>,
    downloadedTracks: List<SongItem>,
    downloadsFlow: StateFlow<Map<String, DownloadProgress>>,
    tasteProfile: TasteProfile?,
    cloudAuthState: CloudAuthUiState,
    ensureNotificationPermission: () -> Unit,
    onNavigateTopTab: (VelunaTopTab) -> Unit,
    onPlayTracks: (List<SongItem>, Int, String) -> Unit,
    onOpenBrowseItem: (BrowseItem) -> Unit,
    onTrackMore: (SongItem) -> Unit,
    onOpenEditHome: () -> Unit,
    onOpenCreatePlaylist: () -> Unit,
    onOpenImportPlaylist: () -> Unit,
    onOpenTasteOnboarding: () -> Unit,
    onOpenEqualizer: () -> Unit
) {
    BackHandler(enabled = isAtTopTabRoot && selectedTopTab != VelunaTopTab.HOME) {
        onNavigateTopTab(VelunaTopTab.HOME)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if ((visitedTabMask and (1 shl VelunaTopTab.HOME.ordinal)) != 0) {
            KeepAliveTabSlot(active = selectedTopTab == VelunaTopTab.HOME) {
                HomeTabPane(
                    navController = navController,
                    container = container,
                    homeSearchVm = homeSearchVm,
                    prefs = prefs,
                    playbackFlow = playbackFlow,
                    likedIds = likedIds,
                    cloudAuthState = cloudAuthState,
                    onNavigateTopTab = onNavigateTopTab,
                    onOpenBrowseItem = onOpenBrowseItem,
                    onTrackMore = onTrackMore,
                    onOpenEditHome = onOpenEditHome
                )
            }
        }

        if ((visitedTabMask and (1 shl VelunaTopTab.SEARCH.ordinal)) != 0) {
            val active = selectedTopTab == VelunaTopTab.SEARCH
            KeepAliveTabSlot(active = active) {
                SearchTabPane(
                    active = active,
                    homeSearchVm = homeSearchVm,
                    playbackFlow = playbackFlow,
                    onNavigateTopTab = onNavigateTopTab,
                    onOpenBrowseItem = onOpenBrowseItem,
                    onTrackMore = onTrackMore
                )
            }
        }

        if ((visitedTabMask and (1 shl VelunaTopTab.LIBRARY.ordinal)) != 0) {
            KeepAliveTabSlot(active = selectedTopTab == VelunaTopTab.LIBRARY) {
                LibraryTabPane(
                    container = container,
                    libraryStatsVm = libraryStatsVm,
                    playbackFlow = playbackFlow,
                    playlists = playlists,
                    downloadedTracks = downloadedTracks,
                    downloadsFlow = downloadsFlow,
                    onOpenBrowseItem = onOpenBrowseItem,
                    onPlayTracks = onPlayTracks,
                    onTrackMore = onTrackMore,
                    onOpenCreatePlaylist = onOpenCreatePlaylist,
                    onOpenImportPlaylist = onOpenImportPlaylist
                )
            }
        }

        if ((visitedTabMask and (1 shl VelunaTopTab.STATS.ordinal)) != 0) {
            KeepAliveTabSlot(active = selectedTopTab == VelunaTopTab.STATS) {
                StatsTabPane(
                    navController = navController,
                    container = container,
                    libraryStatsVm = libraryStatsVm,
                    playbackFlow = playbackFlow,
                    ensureNotificationPermission = ensureNotificationPermission,
                    onTrackMore = onTrackMore
                )
            }
        }

        if ((visitedTabMask and (1 shl VelunaTopTab.SETTINGS.ordinal)) != 0) {
            KeepAliveTabSlot(active = selectedTopTab == VelunaTopTab.SETTINGS) {
                SettingsTabPane(
                    container = container,
                    libraryStatsVm = libraryStatsVm,
                    prefs = prefs,
                    tasteProfile = tasteProfile,
                    cloudAuthState = cloudAuthState,
                    onOpenTasteOnboarding = onOpenTasteOnboarding,
                    onOpenEqualizer = onOpenEqualizer
                )
            }
        }
    }
}

@Composable
private fun KeepAliveTabSlot(
    active: Boolean,
    content: @Composable () -> Unit
) {
    var wasActive by remember { mutableStateOf(active) }
    val alpha = remember { Animatable(if (active) 1f else 0f) }
    LaunchedEffect(active) {
        if (active) {
            if (!wasActive) {
                alpha.snapTo(0.86f)
                alpha.animateTo(1f, tween(durationMillis = 90))
            } else {
                alpha.snapTo(1f)
            }
        } else {
            alpha.snapTo(0f)
        }
        wasActive = active
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(if (active) 1f else 0f)
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    if (active) {
                        placeable.place(0, 0)
                    }
                }
            }
            .graphicsLayer {
                this.alpha = if (active) alpha.value else 0f
            }
            .focusProperties { canFocus = active }
            .semantics {
                if (!active) hideFromAccessibility()
            }
    ) {
        content()
    }
}

@Composable
private fun HomeTabPane(
    navController: NavHostController,
    container: VelunaAppContainer,
    homeSearchVm: HomeSearchViewModel,
    prefs: VelunaUserPrefs,
    playbackFlow: StateFlow<PlaybackUiState>,
    likedIds: Set<String>,
    cloudAuthState: CloudAuthUiState,
    onNavigateTopTab: (VelunaTopTab) -> Unit,
    onOpenBrowseItem: (BrowseItem) -> Unit,
    onTrackMore: (SongItem) -> Unit,
    onOpenEditHome: () -> Unit
) {
    val context = LocalContext.current
    val homeState by homeSearchVm.homeState.collectAsStateWithLifecycle()
    val shortcuts by homeSearchVm.shortcuts.collectAsStateWithLifecycle()
    val jumpBack by homeSearchVm.recentJumpBack.collectAsStateWithLifecycle()
    val familiarArtists by homeSearchVm.familiarArtists.collectAsStateWithLifecycle()
    val nowPlayingSong by remember(playbackFlow) {
        playbackFlow.map { it.nowPlaying }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)
    val artworkAccent by remember(playbackFlow) {
        playbackFlow.map { it.artworkAccentColor }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)

    val onSignInWithGoogle = remember(context, container) { { container.cloudSyncCoordinator.signIn(context) } }
    val onOpenAccountSettings = remember(onNavigateTopTab) { { onNavigateTopTab(VelunaTopTab.SETTINGS) } }
    val onRetry = remember(homeSearchVm, homeState.selectedChipParams) {
        { homeSearchVm.loadHome(homeState.selectedChipParams) }
    }
    val onToggleLike = remember(container) { { song: SongItem -> container.playerController.toggleLike(song) } }
    val onOpenHistory = remember(navController) { { navController.navigate(HistoryRoute) } }

    HomeScreen(
        homeState = homeState,
        prefs = prefs,
        shortcuts = shortcuts,
        jumpBack = jumpBack,
        familiarArtists = familiarArtists,
        nowPlayingSong = nowPlayingSong,
        artworkAccent = artworkAccent,
        likedIds = likedIds,
        cloudAuthState = cloudAuthState,
        onSignInWithGoogle = onSignInWithGoogle,
        onOpenAccountSettings = onOpenAccountSettings,
        onSelectChip = homeSearchVm::loadHome,
        onRetry = onRetry,
        onOpenBrowseItem = onOpenBrowseItem,
        onTrackMore = onTrackMore,
        onToggleLike = onToggleLike,
        onOpenHistory = onOpenHistory,
        onOpenEditHome = onOpenEditHome
    )
}

@Composable
private fun SearchTabPane(
    active: Boolean,
    homeSearchVm: HomeSearchViewModel,
    playbackFlow: StateFlow<PlaybackUiState>,
    onNavigateTopTab: (VelunaTopTab) -> Unit,
    onOpenBrowseItem: (BrowseItem) -> Unit,
    onTrackMore: (SongItem) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(active) {
        if (!active) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }

    val searchState by homeSearchVm.searchState.collectAsStateWithLifecycle()
    val history by homeSearchVm.searchHistory.collectAsStateWithLifecycle()
    val nowPlayingId by remember(playbackFlow) {
        playbackFlow.map { it.nowPlaying?.videoId }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)
    val onOpenMoodTile = remember(homeSearchVm, onNavigateTopTab) {
        { tile: MoodTile ->
            homeSearchVm.openMoodTile(tile)
            onNavigateTopTab(VelunaTopTab.HOME)
        }
    }

    SearchScreen(
        state = searchState,
        searchHistory = history,
        nowPlayingId = nowPlayingId,
        onQueryChange = homeSearchVm::onQueryChange,
        onSubmitSearch = homeSearchVm::submitSearch,
        onClearSearch = homeSearchVm::clearSearchResults,
        onSelectCategory = homeSearchVm::selectSearchCategory,
        onRemoveHistory = homeSearchVm::removeSearchHistoryItem,
        onClearHistory = homeSearchVm::clearSearchHistory,
        onOpenBrowseItem = onOpenBrowseItem,
        onTrackMore = onTrackMore,
        onOpenMoodTile = onOpenMoodTile
    )
}

@Composable
private fun LibraryTabPane(
    container: VelunaAppContainer,
    libraryStatsVm: LibraryStatsViewModel,
    playbackFlow: StateFlow<PlaybackUiState>,
    playlists: List<BrowseItem>,
    downloadedTracks: List<SongItem>,
    downloadsFlow: StateFlow<Map<String, DownloadProgress>>,
    onOpenBrowseItem: (BrowseItem) -> Unit,
    onPlayTracks: (List<SongItem>, Int, String) -> Unit,
    onTrackMore: (SongItem) -> Unit,
    onOpenCreatePlaylist: () -> Unit,
    onOpenImportPlaylist: () -> Unit
) {
    val likedSongs by libraryStatsVm.likedSongs.collectAsStateWithLifecycle()
    val savedAlbums by libraryStatsVm.savedAlbums.collectAsStateWithLifecycle()
    val followedArtists by libraryStatsVm.followedArtists.collectAsStateWithLifecycle()
    val localTracks by libraryStatsVm.localTracks.collectAsStateWithLifecycle()
    val activeDownloads by downloadsFlow.collectAsStateWithLifecycle()
    val nowPlayingId by remember(playbackFlow) {
        playbackFlow.map { it.nowPlaying?.videoId }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)
    val onToggleLike = remember(container) { { song: SongItem -> container.playerController.toggleLike(song) } }

    LibraryScreen(
        playlists = playlists,
        likedSongs = likedSongs,
        savedAlbums = savedAlbums,
        followedArtists = followedArtists,
        downloads = downloadedTracks,
        activeDownloads = activeDownloads,
        localTracks = localTracks,
        nowPlayingId = nowPlayingId,
        onOpenBrowseItem = onOpenBrowseItem,
        onPlaySongs = onPlayTracks,
        onTrackMore = onTrackMore,
        onToggleLike = onToggleLike,
        onOpenCreatePlaylist = onOpenCreatePlaylist,
        onOpenImportPlaylist = onOpenImportPlaylist,
        onImportLocalFiles = libraryStatsVm::importLocalFiles,
        onImportLocalFolder = libraryStatsVm::importLocalFolder,
        onRemoveLocalTrack = libraryStatsVm::removeLocalTrack,
        onDeleteDownload = container.playerController::deleteDownload
    )
}

@Composable
private fun StatsTabPane(
    navController: NavHostController,
    container: VelunaAppContainer,
    libraryStatsVm: LibraryStatsViewModel,
    playbackFlow: StateFlow<PlaybackUiState>,
    ensureNotificationPermission: () -> Unit,
    onTrackMore: (SongItem) -> Unit
) {
    val statsState by libraryStatsVm.statsState.collectAsStateWithLifecycle()
    val nowPlayingId by remember(playbackFlow) {
        playbackFlow.map { it.nowPlaying?.videoId }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)
    val onOpenHistory = remember(navController) { { navController.navigate(HistoryRoute) } }
    val onPlaySong = remember(ensureNotificationPermission, container) {
        { song: SongItem ->
            ensureNotificationPermission()
            container.playerController.playSong(song, "Top Songs")
        }
    }
    val onOpenArtist = remember(navController) { { id: String -> navController.navigate(ArtistRoute(id)) } }
    val onOpenAlbum = remember(navController) { { id: String -> navController.navigate(AlbumRoute(id)) } }

    StatsScreen(
        state = statsState,
        nowPlayingId = nowPlayingId,
        onSelectRange = libraryStatsVm::refreshStats,
        onOpenHistory = onOpenHistory,
        onPlaySong = onPlaySong,
        onTrackMore = onTrackMore,
        onOpenArtist = onOpenArtist,
        onOpenAlbum = onOpenAlbum
    )
}

@Composable
private fun SettingsTabPane(
    container: VelunaAppContainer,
    libraryStatsVm: LibraryStatsViewModel,
    prefs: VelunaUserPrefs,
    tasteProfile: TasteProfile?,
    cloudAuthState: CloudAuthUiState,
    onOpenTasteOnboarding: () -> Unit,
    onOpenEqualizer: () -> Unit
) {
    val context = LocalContext.current
    val blockedArtists by libraryStatsVm.blockedArtists.collectAsStateWithLifecycle()
    val scrobblePendingCount by libraryStatsVm.scrobblePendingCount.collectAsStateWithLifecycle()
    val lastScrobbleError by libraryStatsVm.lastScrobbleError.collectAsStateWithLifecycle()
    val restoredLegacyBackupWarning by libraryStatsVm.restoredLegacyBackupWarning.collectAsStateWithLifecycle()

    val onSignInWithGoogle = remember(context, container) { { container.cloudSyncCoordinator.signIn(context) } }
    val onSignOut = remember(container) { { container.cloudSyncCoordinator.signOut() } }
    val onConfirmSignOutDiscard = remember(container) { { container.cloudSyncCoordinator.confirmSignOutDiscard() } }
    val onDismissSignOutConfirm = remember(container) { { container.cloudSyncCoordinator.dismissSignOutConfirm() } }
    val onDeleteAccount = remember(context, container) { { container.cloudSyncCoordinator.deleteAccount(context) } }
    val onClearDeleteAccountError = remember(container) { { container.cloudSyncCoordinator.clearDeleteAccountError() } }
    val onSyncNow = remember(container) { { container.cloudSyncCoordinator.syncNow() } }

    SettingsScreen(
        prefs = prefs,
        blockedArtists = blockedArtists,
        tasteProfile = tasteProfile,
        cloudAuthState = cloudAuthState,
        scrobblePendingCount = scrobblePendingCount,
        lastScrobbleError = lastScrobbleError,
        restoredLegacyBackupWarning = restoredLegacyBackupWarning,
        onDismissLegacyBackupWarning = libraryStatsVm::dismissLegacyBackupWarning,
        onSignInWithGoogle = onSignInWithGoogle,
        onSignOut = onSignOut,
        onConfirmSignOutDiscard = onConfirmSignOutDiscard,
        onDismissSignOutConfirm = onDismissSignOutConfirm,
        onDeleteAccount = onDeleteAccount,
        onClearDeleteAccountError = onClearDeleteAccountError,
        onSyncNow = onSyncNow,
        onOpenTasteOnboarding = onOpenTasteOnboarding,
        onSetThemePreset = libraryStatsVm::setThemePreset,
        onSetThemeMode = libraryStatsVm::setThemeMode,
        onSetCustomAccent = libraryStatsVm::setCustomAccent,
        onSetAdaptToArtwork = libraryStatsVm::setAdaptToArtwork,
        onSetClassicPlayerLayout = libraryStatsVm::setClassicPlayerLayout,
        onSetCornerRadius = libraryStatsVm::setCornerRadius,
        onSetDisplayName = libraryStatsVm::setDisplayName,
        onSetAudioQuality = libraryStatsVm::setAudioQuality,
        onSetAutoplay = libraryStatsVm::setAutoplay,
        onSetLoudnessNormalization = libraryStatsVm::setLoudnessNormalization,
        onSetHideMusicVideos = libraryStatsVm::setHideMusicVideos,
        onSetAllowMirrorFallbacks = libraryStatsVm::setAllowMirrorFallbacks,
        onOpenEqualizer = onOpenEqualizer,
        onUpdateLyricsProviders = libraryStatsVm::updateLyricsProviders,
        onSaveScrobbling = libraryStatsVm::setScrobbling,
        onUnblockArtist = libraryStatsVm::unblockArtist,
        onBuildBackupJson = libraryStatsVm::buildBackupJson,
        onRestoreBackupJson = libraryStatsVm::restoreBackupJson,
        onStatusMessage = libraryStatsVm::postStatus
    )
}
