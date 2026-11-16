package com.example.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.example.VelunaAppContainer
import com.example.data.cloud.CloudAuthUiState
import com.example.data.local.VelunaUserPrefs
import com.example.model.BrowseItem
import com.example.model.DownloadProgress
import com.example.model.SongItem
import com.example.model.TasteProfile
import com.example.playback.PlaybackUiState
import com.example.ui.navigation.AlbumRoute
import com.example.ui.navigation.ArtistRoute
import com.example.ui.navigation.HistoryRoute
import com.example.ui.navigation.HomeRoute
import com.example.ui.navigation.LibraryRoute
import com.example.ui.navigation.PlaylistRoute
import com.example.ui.navigation.SearchRoute
import com.example.ui.navigation.SettingsRoute
import com.example.ui.navigation.StatsRoute
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
    val context = LocalContext.current

    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        enterTransition = {
            val fromIdx = initialState.destination.topTabIndex()
            val toIdx = targetState.destination.topTabIndex()
            if (fromIdx != null && toIdx != null) {
                val forward = toIdx >= fromIdx
                slideInHorizontally(
                    animationSpec = VelunaMotion.expressiveIntOffsetSpring(),
                    initialOffsetX = { if (forward) (it * 0.12f).toInt() else -(it * 0.12f).toInt() }
                ) + fadeIn(VelunaMotion.fastFade())
            } else {
                slideInHorizontally(
                    animationSpec = VelunaMotion.expressiveIntOffsetSpring(),
                    initialOffsetX = { (it * 0.08f).toInt() }
                ) + fadeIn(VelunaMotion.fastFade())
            }
        },
        exitTransition = {
            val fromIdx = initialState.destination.topTabIndex()
            val toIdx = targetState.destination.topTabIndex()
            if (fromIdx != null && toIdx != null) {
                val forward = toIdx >= fromIdx
                slideOutHorizontally(
                    animationSpec = VelunaMotion.snappyIntOffsetSpring(),
                    targetOffsetX = { if (forward) -(it * 0.08f).toInt() else (it * 0.08f).toInt() }
                ) + fadeOut(VelunaMotion.fastFade())
            } else {
                slideOutHorizontally(
                    animationSpec = VelunaMotion.snappyIntOffsetSpring(),
                    targetOffsetX = { -(it * 0.05f).toInt() }
                ) + fadeOut(VelunaMotion.fastFade())
            }
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

        composable<SearchRoute> {
            val searchState by homeSearchVm.searchState.collectAsStateWithLifecycle()
            val history by homeSearchVm.searchHistory.collectAsStateWithLifecycle()
            val nowPlayingId by remember(playbackFlow) {
                playbackFlow.map { it.nowPlaying?.videoId }.distinctUntilChanged()
            }.collectAsStateWithLifecycle(initialValue = null)

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
                onOpenMoodTile = { tile ->
                    homeSearchVm.openMoodTile(tile)
                    onNavigateTopTab(VelunaTopTab.HOME)
                }
            )
        }

        composable<LibraryRoute> {
            val likedSongs by libraryStatsVm.likedSongs.collectAsStateWithLifecycle()
            val savedAlbums by libraryStatsVm.savedAlbums.collectAsStateWithLifecycle()
            val followedArtists by libraryStatsVm.followedArtists.collectAsStateWithLifecycle()
            val localTracks by libraryStatsVm.localTracks.collectAsStateWithLifecycle()
            val activeDownloads by downloadsFlow.collectAsStateWithLifecycle()
            val nowPlayingId by remember(playbackFlow) {
                playbackFlow.map { it.nowPlaying?.videoId }.distinctUntilChanged()
            }.collectAsStateWithLifecycle(initialValue = null)

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
                onToggleLike = { container.playerController.toggleLike(it) },
                onOpenCreatePlaylist = onOpenCreatePlaylist,
                onOpenImportPlaylist = onOpenImportPlaylist,
                onImportLocalFiles = libraryStatsVm::importLocalFiles,
                onImportLocalFolder = libraryStatsVm::importLocalFolder,
                onRemoveLocalTrack = libraryStatsVm::removeLocalTrack,
                onDeleteDownload = container.playerController::deleteDownload
            )
        }

        composable<StatsRoute> {
            val statsState by libraryStatsVm.statsState.collectAsStateWithLifecycle()
            val nowPlayingId by remember(playbackFlow) {
                playbackFlow.map { it.nowPlaying?.videoId }.distinctUntilChanged()
            }.collectAsStateWithLifecycle(initialValue = null)
            StatsScreen(
                state = statsState,
                nowPlayingId = nowPlayingId,
                onSelectRange = libraryStatsVm::refreshStats,
                onOpenHistory = { navController.navigate(HistoryRoute) },
                onPlaySong = { song ->
                    ensureNotificationPermission()
                    container.playerController.playSong(song, "Top Songs")
                },
                onTrackMore = onTrackMore,
                onOpenArtist = { id -> navController.navigate(ArtistRoute(id)) },
                onOpenAlbum = { id -> navController.navigate(AlbumRoute(id)) }
            )
        }

        composable<SettingsRoute> {
            val blockedArtists by libraryStatsVm.blockedArtists.collectAsStateWithLifecycle()
            val scrobblePendingCount by libraryStatsVm.scrobblePendingCount.collectAsStateWithLifecycle()
            val lastScrobbleError by libraryStatsVm.lastScrobbleError.collectAsStateWithLifecycle()
            val restoredLegacyBackupWarning by libraryStatsVm.restoredLegacyBackupWarning.collectAsStateWithLifecycle()

            SettingsScreen(
                prefs = prefs,
                blockedArtists = blockedArtists,
                tasteProfile = tasteProfile,
                cloudAuthState = cloudAuthState,
                scrobblePendingCount = scrobblePendingCount,
                lastScrobbleError = lastScrobbleError,
                restoredLegacyBackupWarning = restoredLegacyBackupWarning,
                onDismissLegacyBackupWarning = libraryStatsVm::dismissLegacyBackupWarning,
                onSignInWithGoogle = { container.cloudSyncCoordinator.signIn(context) },
                onSignOut = { container.cloudSyncCoordinator.signOut() },
                onConfirmSignOutDiscard = { container.cloudSyncCoordinator.confirmSignOutDiscard() },
                onDismissSignOutConfirm = { container.cloudSyncCoordinator.dismissSignOutConfirm() },
                onDeleteAccount = { container.cloudSyncCoordinator.deleteAccount(context) },
                onClearDeleteAccountError = { container.cloudSyncCoordinator.clearDeleteAccountError() },
                onSyncNow = { container.cloudSyncCoordinator.syncNow() },
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

private fun NavDestination?.topTabIndex(): Int? = when {
    this?.hasRoute<HomeRoute>() == true -> VelunaTopTab.HOME.ordinal
    this?.hasRoute<SearchRoute>() == true -> VelunaTopTab.SEARCH.ordinal
    this?.hasRoute<LibraryRoute>() == true -> VelunaTopTab.LIBRARY.ordinal
    this?.hasRoute<StatsRoute>() == true -> VelunaTopTab.STATS.ordinal
    this?.hasRoute<SettingsRoute>() == true -> VelunaTopTab.SETTINGS.ordinal
    else -> null
}
