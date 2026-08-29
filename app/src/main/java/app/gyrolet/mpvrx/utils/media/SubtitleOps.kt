/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.media

import android.util.Log
import app.gyrolet.mpvrx.data.network.client.NetworkMimeTypes
import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.domain.network.NetworkPath
import app.gyrolet.mpvrx.preferences.MpvConfigControlledFeatures
import app.gyrolet.mpvrx.preferences.MpvConfigOverridePolicy
import app.gyrolet.mpvrx.repository.NetworkRepository
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.net.URLDecoder
import java.util.Locale

/**
 * Simple utility for automatically loading subtitle files.
 * Finds and matches subtitles in the same directory (or Subs/ subdirectories) for both
 * local and network files (SMB, FTP, WebDAV).
 */
object SubtitleOps : KoinComponent {
  private const val TAG = "SubtitleOps"
  private val networkRepository: NetworkRepository by inject()

  private val SUB_DIRECTORY_NAMES = setOf("subs", "subtitles", "sub", "subtitle")

  suspend fun autoloadSubtitles(
    videoFilePath: String,
    videoFileName: String,
    networkConnectionId: Long = -1L,
    expectedGeneration: Long? = null,
    autoSelect: Boolean = true,
  ) = withContext(Dispatchers.IO) {
    try {
      if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.SUBTITLE_DISCOVERY)) return@withContext
      if (!isGenerationCurrent(expectedGeneration)) return@withContext
      // Skip file descriptor URIs (these don't have a parent directory concept)
      if (videoFilePath.startsWith("fd://")) return@withContext

      // For content:// URIs, we can't autoload (no access to parent directory)
      if (videoFilePath.startsWith("content://")) return@withContext

      // Check if this is a network file with connection ID (SMB/FTP/WebDAV via proxy)
      if (networkConnectionId != -1L) {
        // For network files, scan the directory using network client
        autoloadNetworkFileSubtitles(videoFilePath, videoFileName, networkConnectionId, expectedGeneration, autoSelect)
        return@withContext
      }

      // Check if this is a direct network stream (http, https, ftp, ftps, smb, webdav, etc.)
      val isNetworkStream = videoFilePath.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*"))

      if (isNetworkStream) {
        Log.d(TAG, "Skipping direct network subtitle autoload for: $videoFilePath")
        return@withContext
      } else {
        // For local files, scan the directory
        autoloadLocalSubtitles(videoFilePath, videoFileName, expectedGeneration, autoSelect)
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (e: Exception) {
      Log.e(TAG, "Error loading subtitles", e)
    }
  }

  /**
   * Autoload subtitles for network files (SMB/FTP/WebDAV)
   * Lists files in the same directory and loads matching subtitle files via proxy
   */
  private suspend fun autoloadNetworkFileSubtitles(
    videoFilePath: String,
    videoFileName: String,
    networkConnectionId: Long,
    expectedGeneration: Long?,
    autoSelect: Boolean,
  ) {
    try {
      Log.d(TAG, "Autoloading subtitles for network file: $videoFilePath")

      // Get the network connection
      val connection = networkRepository.getConnectionById(networkConnectionId)
      if (connection == null) {
        Log.w(TAG, "Network connection not found: $networkConnectionId")
        return
      }

      // Get the directory path (parent of the video file)
      val normalizedVideoPath = NetworkPath.from(videoFilePath)
      val directorySegments = normalizedVideoPath.segments.dropLast(1)
      val directoryPath =
        if (directorySegments.isEmpty()) "/" else "/${directorySegments.joinToString("/")}"

      Log.d(TAG, "Scanning directory: $directoryPath")

      // List files in the parent directory
      val filesResult = networkRepository.listFiles(connection, directoryPath)
      if (filesResult.isFailure) {
        Log.w(TAG, "Failed to list network directory: ${filesResult.exceptionOrNull()?.message}")
        return
      }

      val parentFiles = filesResult.getOrNull() ?: emptyList()
      if (!isGenerationCurrent(expectedGeneration)) return

      // Also scan Subs/ or Subtitles/ subdirectories if present
      val allCandidateFiles = parentFiles.toMutableList()
      val subDirs = parentFiles.filter { it.isDirectory && it.name.lowercase(Locale.ROOT) in SUB_DIRECTORY_NAMES }
      for (subDir in subDirs) {
        if (!isGenerationCurrent(expectedGeneration)) return
        val subFilesResult = networkRepository.listFiles(connection, subDir.path)
        if (subFilesResult.isSuccess) {
          allCandidateFiles.addAll(subFilesResult.getOrNull().orEmpty())
        }
      }

      val allFileNames = allCandidateFiles.map { it.name }
      val cleanVideoName =
        runCatching { URLDecoder.decode(videoFileName, "UTF-8") }.getOrDefault(videoFileName)
          .substringAfterLast('/')
          .substringAfterLast('\\')

      // Filter for subtitle files that match the video file
      val subtitles =
        allCandidateFiles.filter { file ->
          !file.isDirectory &&
            isSubtitleFile(file.name) &&
            isSubtitleMatching(file.name, cleanVideoName, allFileNames)
        }

      if (subtitles.isEmpty()) {
        Log.d(TAG, "No matching subtitle files found for: $cleanVideoName")
        return
      }

      Log.d(TAG, "Found ${subtitles.size} matching network subtitle file(s)")

      // Load subtitles via proxy
      withContext(Dispatchers.Main) {
        subtitles.forEachIndexed { index, subtitle ->
          var registeredProxyUrl: String? = null
          try {
            if (!isGenerationCurrent(expectedGeneration)) return@forEachIndexed
            val displayName =
              subtitle.name
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .takeIf { it.isNotBlank() } ?: subtitle.name

            val mimeType = NetworkMimeTypes.forFileName(subtitle.name) ?: "text/plain"
            val proxyUrl =
              PlaybackSession.registerAuxiliaryNetworkStream(
                connectionId = connection.id,
                filePath = subtitle.path,
                fileSize = subtitle.size,
                mimeType = mimeType,
                expectedGeneration = expectedGeneration,
              ) ?: return@forEachIndexed
            registeredProxyUrl = proxyUrl

            // Use "select" for the first subtitle if autoSelect is true, "auto" for others
            val flag = if (index == 0 && autoSelect) "select" else "auto"
            val added =
              expectedGeneration?.let { generation ->
                PlaybackSession.commandForGeneration(generation, "sub-add", proxyUrl, flag, displayName)
              } ?: run {
                PlaybackSession.command("sub-add", proxyUrl, flag, displayName)
                true
              }
            if (!added) {
              PlaybackSession.unregisterAuxiliaryNetworkStream(proxyUrl)
              registeredProxyUrl = null
              return@forEachIndexed
            }
            Log.d(TAG, "Loaded network subtitle: '$displayName' via proxy (flag=$flag)")
            registeredProxyUrl = null
          } catch (cancellation: CancellationException) {
            registeredProxyUrl?.let(PlaybackSession::unregisterAuxiliaryNetworkStream)
            throw cancellation
          } catch (e: Exception) {
            registeredProxyUrl?.let(PlaybackSession::unregisterAuxiliaryNetworkStream)
            Log.e(TAG, "Failed to load subtitle ${subtitle.name}: ${e.message}", e)
          }
        }
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (e: Exception) {
      Log.e(TAG, "Error autoloading network subtitles", e)
    }
  }

  private suspend fun autoloadLocalSubtitles(
    videoFilePath: String,
    videoFileName: String,
    expectedGeneration: Long?,
    autoSelect: Boolean,
  ) {
    val cleanVideoPath =
      runCatching { URLDecoder.decode(videoFilePath.removePrefix("file://"), "UTF-8") }.getOrDefault(videoFilePath)
    val videoFile = File(cleanVideoPath)
    val videoDirectory = videoFile.parentFile ?: return
    val cleanVideoName =
      runCatching { URLDecoder.decode(videoFileName, "UTF-8") }.getOrDefault(videoFileName)
        .substringAfterLast('/')
        .substringAfterLast('\\')

    val dirFiles = videoDirectory.listFiles()?.toList() ?: emptyList()
    val allCandidateFiles = dirFiles.toMutableList()

    // Also scan Subs/ or Subtitles/ subdirectories if present
    val subDirs = dirFiles.filter { it.isDirectory && it.name.lowercase(Locale.ROOT) in SUB_DIRECTORY_NAMES }
    for (subDir in subDirs) {
      allCandidateFiles.addAll(subDir.listFiles()?.toList() ?: emptyList())
    }

    val allFileNames = allCandidateFiles.map { it.name }
    val subtitles =
      allCandidateFiles.filter { file ->
        file.isFile &&
          isSubtitleFile(file.name) &&
          isSubtitleMatching(file.name, cleanVideoName, allFileNames)
      }

    if (subtitles.isNotEmpty()) {
      withContext(Dispatchers.Main) {
        subtitles.forEachIndexed { index, subtitle ->
          if (!isGenerationCurrent(expectedGeneration)) return@forEachIndexed
          val flag = if (index == 0 && autoSelect) "select" else "auto"
          val added =
            expectedGeneration?.let { generation ->
              PlaybackSession.commandForGeneration(
                generation,
                "sub-add",
                subtitle.absolutePath,
                flag,
                subtitle.name,
              )
            } ?: run {
              PlaybackSession.command("sub-add", subtitle.absolutePath, flag, subtitle.name)
              true
            }
          if (!added) return@forEachIndexed
          Log.d(TAG, "Loaded local subtitle: ${subtitle.name} (flag=$flag)")
        }
      }
    }
  }

  fun isSubtitleMatching(
    subtitleFileName: String,
    videoFileName: String,
    allFilesInDir: List<String> = emptyList(),
  ): Boolean {
    val cleanVideo =
      runCatching { URLDecoder.decode(videoFileName, "UTF-8") }.getOrDefault(videoFileName)
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .trim()
    val cleanSub =
      runCatching { URLDecoder.decode(subtitleFileName, "UTF-8") }.getOrDefault(subtitleFileName)
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .trim()

    if (!isSubtitleFile(cleanSub)) return false

    val videoBase = cleanVideo.substringBeforeLast('.').trim()
    val subBase = cleanSub.substringBeforeLast('.').trim()

    if (videoBase.isEmpty() || subBase.isEmpty()) return false

    // 1. Exact match (case-insensitive)
    if (subBase.equals(videoBase, ignoreCase = true)) return true

    // 2. Subtitle has language or tag suffix: "video.en.srt", "video.zh-Hans.ass", "video_eng.srt", "video [en].srt"
    val delimiterRegex = Regex("^[._\\-\\s\\[(]")
    if (subBase.startsWith(videoBase, ignoreCase = true)) {
      val remaining = subBase.substring(videoBase.length)
      if (remaining.isEmpty() || delimiterRegex.containsMatchIn(remaining)) {
        return true
      }
    }

    // 3. Video name has extra tags/resolution that subtitle does not have
    // e.g. video: "Movie.2024.1080p.WEBRip.mkv", sub: "Movie.2024.srt" or "Movie.2024.en.srt"
    if (videoBase.startsWith(subBase, ignoreCase = true)) {
      val remaining = videoBase.substring(subBase.length)
      if (remaining.isEmpty() || delimiterRegex.containsMatchIn(remaining)) {
        return true
      }
    }
    val subBaseWithoutLang = subBase.substringBeforeLast('.').substringBeforeLast('_').substringBeforeLast('-').trim()
    if (subBaseWithoutLang.isNotBlank() && videoBase.startsWith(subBaseWithoutLang, ignoreCase = true)) {
      val remaining = videoBase.substring(subBaseWithoutLang.length)
      if (remaining.isEmpty() || delimiterRegex.containsMatchIn(remaining)) {
        return true
      }
    }

    // 4. Episode pattern matching (e.g. S01E02, E02, EP02, Episode 02, - 02)
    val episodePattern =
      Regex("(?i)(?:s\\d+)?(?:e|ep|episode)[._\\-\\s]*(\\d{1,4})|[\\[\\(_\\-\\s](\\d{1,4})[\\]\\)_\\-\\s]")
    val videoEpMatches =
      episodePattern.findAll(videoBase).mapNotNull {
        it.groupValues[1].ifEmpty { it.groupValues[2] }.toIntOrNull()
      }.toList()
    val subEpMatches =
      episodePattern.findAll(subBase).mapNotNull {
        it.groupValues[1].ifEmpty { it.groupValues[2] }.toIntOrNull()
      }.toList()

    if (videoEpMatches.isNotEmpty() && subEpMatches.isNotEmpty()) {
      val commonEp = videoEpMatches.intersect(subEpMatches.toSet())
      if (commonEp.isNotEmpty()) {
        val videoPrefix = videoBase.take(6).lowercase(Locale.ROOT)
        val subPrefix = subBase.take(6).lowercase(Locale.ROOT)
        if (videoPrefix.isEmpty() ||
          subPrefix.isEmpty() ||
          videoBase.contains(subBase.take(6), ignoreCase = true) ||
          subBase.contains(videoBase.take(6), ignoreCase = true) ||
          videoPrefix == subPrefix
        ) {
          return true
        }
      }
    }

    // 5. If directory contains only ONE video file, all subtitles in directory belong to it
    if (allFilesInDir.isNotEmpty()) {
      val videoCount = allFilesInDir.count { fileName ->
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        ext in FileTypeUtils.VIDEO_EXTENSIONS
      }
      if (videoCount == 1) {
        return true
      }
    }

    return false
  }

  fun isSubtitleFile(fileName: String): Boolean {
    val cleanName = fileName.substringBefore('?').substringBefore('#')
    val extension = cleanName.substringAfterLast('.', "").lowercase(Locale.getDefault())
    return extension in
      setOf(
        // Common & modern
        "srt",
        "vtt",
        "ass",
        "ssa",
        // DVD / Blu-ray
        "sub",
        "idx",
        "sup",
        // Streaming / XML / Professional
        "xml",
        "ttml",
        "dfxp",
        "itt",
        "ebu",
        "imsc",
        "usf",
        // Online platforms
        "sbv",
        "srv1",
        "srv2",
        "srv3",
        "json",
        // Legacy & niche
        "sami",
        "smi",
        "mpl",
        "pjs",
        "stl",
        "rt",
        "psb",
        "cap",
        // Broadcast captions
        "scc",
        "vttx",
        // Karaoke / lyrics
        "lrc",
        "krc",
        // Fallback / raw text
        "txt",
        "pgs",
      )
  }

  private fun isGenerationCurrent(expectedGeneration: Long?): Boolean =
    expectedGeneration == null || PlaybackSession.isCurrentGeneration(expectedGeneration)
}
