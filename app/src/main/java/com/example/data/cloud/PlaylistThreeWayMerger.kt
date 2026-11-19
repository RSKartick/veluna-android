package com.example.data.cloud

import com.example.model.SongItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PlaylistBaselineSnapshot(
    val title: String,
    val description: String,
    val remoteCoverUri: String? = null,
    val coverRemoved: Boolean = false,
    val trackIds: List<String> = emptyList()
)

data class MergedPlaylistResult(
    val title: String,
    val description: String,
    val remoteCoverUri: String?,
    val coverRemoved: Boolean,
    val tracks: List<SongItem>,
    val needsLocalUpdate: Boolean,
    val needsCloudPush: Boolean
)

internal object PlaylistThreeWayMerger {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeBaselineSnapshot(snapshot: PlaylistBaselineSnapshot): String =
        json.encodeToString(snapshot)

    fun decodeBaselineSnapshot(raw: String?): PlaylistBaselineSnapshot? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<PlaylistBaselineSnapshot>(raw) }.getOrNull()
    }

    /**
     * Performs deterministic 3-way merge of playlist metadata and ordered tracks against [baseline].
     * Preserves non-conflicting concurrent additions and removals across offline devices while
     * breaking conflicting same-field mutations or track order ties by newer client timestamp.
     */
    fun merge(
        baseline: PlaylistBaselineSnapshot?,
        localTitle: String,
        localDescription: String,
        localRemoteCoverUri: String?,
        localCoverRemoved: Boolean,
        localTracks: List<SongItem>,
        localUpdatedAt: Long,
        remoteTitle: String,
        remoteDescription: String,
        remoteCoverUri: String?,
        remoteCoverRemoved: Boolean,
        remoteTracks: List<SongItem>,
        remoteUpdatedAt: Long
    ): MergedPlaylistResult {
        val remoteNewer = remoteUpdatedAt > localUpdatedAt

        val mergedTitle: String
        val mergedDescription: String
        val mergedRemoteCover: String?
        val mergedCoverRemoved: Boolean

        if (baseline != null) {
            val localTitleChanged = localTitle != baseline.title
            val remoteTitleChanged = remoteTitle != baseline.title
            mergedTitle = when {
                localTitleChanged && !remoteTitleChanged -> localTitle
                !localTitleChanged && remoteTitleChanged -> remoteTitle
                localTitleChanged && remoteTitleChanged -> if (remoteNewer) remoteTitle else localTitle
                else -> baseline.title
            }

            val localDescChanged = localDescription != baseline.description
            val remoteDescChanged = remoteDescription != baseline.description
            mergedDescription = when {
                localDescChanged && !remoteDescChanged -> localDescription
                !localDescChanged && remoteDescChanged -> remoteDescription
                localDescChanged && remoteDescChanged -> if (remoteNewer) remoteDescription else localDescription
                else -> baseline.description
            }

            val localCoverStateChanged = localRemoteCoverUri != baseline.remoteCoverUri || localCoverRemoved != baseline.coverRemoved
            val remoteCoverStateChanged = remoteCoverUri != baseline.remoteCoverUri || remoteCoverRemoved != baseline.coverRemoved
            val (chosenCover, chosenRemoved) = when {
                localCoverStateChanged && !remoteCoverStateChanged -> localRemoteCoverUri to localCoverRemoved
                !localCoverStateChanged && remoteCoverStateChanged -> remoteCoverUri to remoteCoverRemoved
                localCoverStateChanged && remoteCoverStateChanged -> {
                    if (remoteNewer) (remoteCoverUri to remoteCoverRemoved) else (localRemoteCoverUri to localCoverRemoved)
                }
                else -> baseline.remoteCoverUri to baseline.coverRemoved
            }
            mergedRemoteCover = if (chosenRemoved) null else chosenCover
            mergedCoverRemoved = chosenRemoved
        } else {
            mergedTitle = if (remoteNewer) remoteTitle else localTitle
            mergedDescription = if (remoteNewer) remoteDescription else localDescription
            val chosenRemoved = if (remoteNewer) remoteCoverRemoved else localCoverRemoved
            val chosenCover = if (remoteNewer) {
                remoteCoverUri ?: localRemoteCoverUri.takeIf { !remoteCoverRemoved }
            } else {
                localRemoteCoverUri ?: remoteCoverUri.takeIf { !localCoverRemoved }
            }
            mergedCoverRemoved = chosenRemoved
            mergedRemoteCover = if (chosenRemoved) null else chosenCover
        }

        val songById = LinkedHashMap<String, SongItem>()
        if (remoteNewer) {
            localTracks.forEach { songById[it.videoId] = it }
            remoteTracks.forEach { songById[it.videoId] = it }
        } else {
            remoteTracks.forEach { songById[it.videoId] = it }
            localTracks.forEach { songById[it.videoId] = it }
        }

        val localIds = localTracks.map { it.videoId }.distinct()
        val remoteIds = remoteTracks.map { it.videoId }.distinct()

        val mergedIds: List<String> = if (baseline != null) {
            val baseIds = baseline.trackIds.distinct()
            val baseSet = baseIds.toSet()
            val localSet = localIds.toSet()
            val remoteSet = remoteIds.toSet()

            val removedByLocal = baseSet - localSet
            val removedByRemote = baseSet - remoteSet

            val survivingBase = baseSet - removedByLocal - removedByRemote
            val addedByLocal = localIds.filter { it !in baseSet }
            val addedByRemote = remoteIds.filter { it !in baseSet }
            val targetSet = survivingBase + addedByLocal + addedByRemote

            val localReordered = localIds.filter { it in survivingBase } != baseIds.filter { it in survivingBase }
            val remoteReordered = remoteIds.filter { it in survivingBase } != baseIds.filter { it in survivingBase }

            val preferRemoteOrder = when {
                remoteReordered && !localReordered -> true
                localReordered && !remoteReordered -> false
                else -> remoteNewer
            }

            val ordered = LinkedHashSet<String>(targetSet.size)
            if (preferRemoteOrder) {
                remoteIds.filterTo(ordered) { it in targetSet }
                addedByLocal.filterTo(ordered) { it in targetSet }
            } else {
                localIds.filterTo(ordered) { it in targetSet }
                addedByRemote.filterTo(ordered) { it in targetSet }
            }
            ordered.toList()
        } else {
            // No prior baseline (two independent offline creations sharing a legacy/cloud key): union tracks
            val ordered = LinkedHashSet<String>()
            if (remoteNewer) {
                remoteIds.forEach { ordered.add(it) }
                localIds.forEach { ordered.add(it) }
            } else {
                localIds.forEach { ordered.add(it) }
                remoteIds.forEach { ordered.add(it) }
            }
            ordered.toList()
        }

        val mergedTracks = mergedIds.mapNotNull { songById[it] }

        val localMatchesMerged =
            localTitle == mergedTitle &&
                localDescription == mergedDescription &&
                localRemoteCoverUri == mergedRemoteCover &&
                localCoverRemoved == mergedCoverRemoved &&
                localIds == mergedIds

        val remoteMatchesMerged =
            remoteTitle == mergedTitle &&
                remoteDescription == mergedDescription &&
                remoteCoverUri == mergedRemoteCover &&
                remoteCoverRemoved == mergedCoverRemoved &&
                remoteIds == mergedIds

        return MergedPlaylistResult(
            title = mergedTitle,
            description = mergedDescription,
            remoteCoverUri = mergedRemoteCover,
            coverRemoved = mergedCoverRemoved,
            tracks = mergedTracks,
            needsLocalUpdate = !localMatchesMerged,
            needsCloudPush = !remoteMatchesMerged
        )
    }
}
