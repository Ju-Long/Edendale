package com.babasama.edendale.connectors

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The video file extensions a library imports. Extensions, not MIME types: providers disagree on video types. */
object VideoFiles {
    val extensions: Set<String> = setOf(
        "mkv", "mp4", "m4v", "mov", "avi", "wmv", "flv", "webm",
        "ts", "m2ts", "mts", "mpg", "mpeg", "3gp", "ogv", "vob",
    )

    fun isVideoName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in extensions
}

/**
 * One item in a listed directory: a subfolder to drill into, or a file.
 * [url] is canonical and credential-free (see [SourceUrl]).
 */
data class ConnectorEntry(
    val name: String,
    val url: String,
    val isDirectory: Boolean,
    /** Bytes, when the listing reports it. */
    val size: Long? = null,
    /** Seconds, when the provider reports it (Drive `videoMediaMetadata`, the Graph `video` facet). */
    val durationSeconds: Double? = null,
    val modifiedEpochMillis: Long? = null,
) {
    /** A file with one of the video extensions the library imports. */
    val isVideo: Boolean get() = !isDirectory && VideoFiles.isVideoName(name)

    /** Hidden files and folders: `.DS_Store`, `._Movie.mkv`, `.Trash`. */
    val isHidden: Boolean get() = name.startsWith(".")
}

/**
 * Every video a walk found, and whether the walk saw the whole tree. A
 * partial walk (an unreadable folder, or the folder cap) must never delete
 * library rows for files it didn't reach.
 */
data class Enumeration(val videos: List<ConnectorEntry>, val complete: Boolean)

/**
 * A connection to a remote file tree that can verify itself and list
 * directories (H.1, Apple's `MediaConnector`). Implementations capture the
 * address and how to authenticate; nothing they return carries a password
 * or token.
 */
interface MediaConnector {
    val kind: MediaSourceKind

    /** Top of the browsable tree, such as `smb://host/` where the shares list, or an account's roots. */
    val root: String

    /** The username or account email shown with the source; never a secret. */
    val accountLabel: String? get() = null

    /** Confirms the source is reachable and the login or account works. */
    suspend fun validate() {
        list(root)
    }

    /** Lists one directory (not recursive). */
    suspend fun list(directory: String): List<ConnectorEntry>

    /**
     * Every video under [folder]. The default walks [list] breadth-first;
     * providers with recursive listings (Dropbox, OneDrive) override it.
     */
    suspend fun enumerateVideos(folder: String): Enumeration = ConnectorWalk.videos(folder, list = ::list)

    /** Whether [directory] can become a library source; a virtual folder that only gathers others can't. */
    fun canIndex(directory: String): Boolean = true
}

/** The breadth-first walk behind the default [MediaConnector.enumerateVideos]. */
object ConnectorWalk {
    /** Caps runaway trees: symlink cycles and shortcut loops have no other guard. */
    const val MAX_DIRECTORIES = 2_000

    /**
     * A failure listing [folder] itself throws (the source is unreachable); a
     * failure below it skips that branch and marks the walk incomplete.
     */
    suspend fun videos(
        folder: String,
        maxDirectories: Int = MAX_DIRECTORIES,
        list: suspend (String) -> List<ConnectorEntry>,
    ): Enumeration {
        val videos = mutableListOf<ConnectorEntry>()
        val queue = ArrayDeque(listOf(folder))
        val visited = hashSetOf(folder)
        var listed = 0
        var complete = true

        while (queue.isNotEmpty()) {
            if (listed >= maxDirectories) {
                complete = false
                break
            }
            currentCoroutineContext().ensureActive()
            val directory = queue.removeFirst()
            val entries = if (listed == 0) {
                list(directory)
            } else {
                try {
                    list(directory)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    complete = false
                    emptyList()
                }
            }
            listed += 1

            for (entry in entries) {
                if (entry.isHidden) continue
                if (entry.isDirectory) {
                    if (visited.add(entry.url)) queue.addLast(entry.url)
                } else if (entry.isVideo) {
                    videos += entry
                }
            }
        }
        return Enumeration(videos, complete)
    }
}
