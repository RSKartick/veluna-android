package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.DownloadProgress
import com.example.model.DownloadStatus
import com.example.model.SongItem
import com.example.ui.components.TrackRow
import com.example.ui.theme.velunaOutlinedButtonBorder
import com.example.ui.theme.velunaOutlinedButtonColors
import com.example.ui.theme.velunaPrimaryButtonColors

@Composable
internal fun LibraryDownloadsTab(
    downloads: List<SongItem>,
    activeDownloads: Map<String, DownloadProgress>,
    nowPlayingId: String?,
    onPlaySongs: (List<SongItem>, Int, String) -> Unit,
    onTrackMore: (SongItem) -> Unit,
    onDeleteDownload: (String) -> Unit
) {
    val activeList = activeDownloads.values.filter {
        it.status == DownloadStatus.DOWNLOADING ||
            it.status == DownloadStatus.QUEUED ||
            it.status == DownloadStatus.FAILED
    }
    if (activeList.isEmpty() && downloads.isEmpty()) {
        EmptyLibraryNotice(
            title = stringResource(R.string.library_empty_downloads_title),
            body = stringResource(R.string.library_empty_downloads_body)
        )
    } else {
        val downloadsLabel = stringResource(R.string.library_tab_downloads)
        LazyColumn(
            contentPadding = PaddingValues(bottom = 28.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(activeList, key = { "dl_active_${it.videoId}" }) { prog ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(prog.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(prog.error ?: prog.status.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { prog.progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            if (downloads.isNotEmpty()) {
                item {
                    PlayShuffleHeader(
                        countLabel = stringResource(R.string.library_offline_count, downloads.size),
                        onPlayAll = { onPlaySongs(downloads, 0, downloadsLabel) },
                        onShuffleAll = { onPlaySongs(downloads.shuffled(), 0, downloadsLabel) }
                    )
                }
            }

            items(downloads, key = { "dl_done_${it.videoId}" }) { song ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
                        TrackRow(
                            song = song,
                            isPlaying = nowPlayingId == song.videoId,
                            isDownloaded = true,
                            onClick = {
                                val idx = downloads.indexOfFirst { it.videoId == song.videoId }.coerceAtLeast(0)
                                onPlaySongs(downloads, idx, downloadsLabel)
                            },
                            onMoreClick = { onTrackMore(song) }
                        )
                    }
                    IconButton(
                        onClick = { onDeleteDownload(song.videoId) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.library_delete_download),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun LibraryLocalMusicTab(
    localTracks: List<SongItem>,
    nowPlayingId: String?,
    onPlaySongs: (List<SongItem>, Int, String) -> Unit,
    onTrackMore: (SongItem) -> Unit,
    onImportLocalFiles: (List<Uri>) -> Unit,
    onImportLocalFolder: (Uri) -> Unit,
    onRemoveLocalTrack: (String) -> Unit
) {
    val pickFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onImportLocalFiles(uris)
    }

    val pickFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) onImportLocalFolder(treeUri)
    }

    val localMusicLabel = stringResource(R.string.library_tab_local_music)

    LazyColumn(
        contentPadding = PaddingValues(bottom = 28.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { pickFilesLauncher.launch(arrayOf("audio/*")) },
                    colors = velunaOutlinedButtonColors(),
                    border = velunaOutlinedButtonBorder(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("local_pick_files_btn")
                ) {
                    Icon(Icons.Rounded.AudioFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.library_add_files), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
                Button(
                    onClick = { pickFolderLauncher.launch(null) },
                    colors = velunaPrimaryButtonColors(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("local_pick_folder_btn")
                ) {
                    Icon(Icons.Rounded.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.library_scan_folder), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        if (localTracks.isEmpty()) {
            item {
                EmptyLibraryNotice(
                    title = stringResource(R.string.library_empty_local_title),
                    body = stringResource(R.string.library_empty_local_body)
                )
            }
        } else {
            item {
                PlayShuffleHeader(
                    countLabel = stringResource(R.string.library_local_count, localTracks.size),
                    onPlayAll = { onPlaySongs(localTracks, 0, localMusicLabel) },
                    onShuffleAll = { onPlaySongs(localTracks.shuffled(), 0, localMusicLabel) }
                )
            }
            items(localTracks, key = { it.videoId }) { song ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
                        TrackRow(
                            song = song,
                            isPlaying = nowPlayingId == song.videoId,
                            onClick = {
                                val idx = localTracks.indexOfFirst { it.videoId == song.videoId }.coerceAtLeast(0)
                                onPlaySongs(localTracks, idx, localMusicLabel)
                            },
                            onMoreClick = { onTrackMore(song) }
                        )
                    }
                    IconButton(
                        onClick = { song.localUri?.let(onRemoveLocalTrack) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.library_remove_local),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlayShuffleHeader(
    countLabel: String,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = countLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(
            onClick = onShuffleAll,
            colors = velunaOutlinedButtonColors(),
            border = velunaOutlinedButtonBorder(),
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.action_shuffle), maxLines = 1, softWrap = false)
        }
        Button(
            onClick = onPlayAll,
            colors = velunaPrimaryButtonColors(),
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.action_play), maxLines = 1, softWrap = false)
        }
    }
}

@Composable
internal fun EmptyLibraryNotice(title: String, body: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}
