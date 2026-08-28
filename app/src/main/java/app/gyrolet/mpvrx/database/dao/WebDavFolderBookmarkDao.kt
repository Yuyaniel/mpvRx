/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.gyrolet.mpvrx.database.entities.WebDavFolderBookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WebDavFolderBookmarkDao {
  @Query(
    """
    SELECT * FROM webdav_folder_bookmarks
    ORDER BY createdAt DESC, id DESC
    """,
  )
  fun observeAll(): Flow<List<WebDavFolderBookmarkEntity>>

  @Query(
    """
    SELECT * FROM webdav_folder_bookmarks
    WHERE connectionId = :connectionId
    ORDER BY createdAt DESC, id DESC
    """,
  )
  fun observeByConnection(connectionId: Long): Flow<List<WebDavFolderBookmarkEntity>>

  @Query(
    """
    SELECT EXISTS(
      SELECT 1 FROM webdav_folder_bookmarks
      WHERE connectionId = :connectionId AND folderPath = :folderPath
    )
    """,
  )
  fun observeIsBookmarked(
    connectionId: Long,
    folderPath: String,
  ): Flow<Boolean>

  @Query(
    """
    SELECT * FROM webdav_folder_bookmarks
    WHERE connectionId = :connectionId AND folderPath = :folderPath
    LIMIT 1
    """,
  )
  suspend fun findByConnectionAndPath(
    connectionId: Long,
    folderPath: String,
  ): WebDavFolderBookmarkEntity?

  @Upsert
  suspend fun upsert(bookmark: WebDavFolderBookmarkEntity)

  @Query("DELETE FROM webdav_folder_bookmarks WHERE id = :id")
  suspend fun deleteById(id: Long)

  @Query(
    """
    DELETE FROM webdav_folder_bookmarks
    WHERE connectionId = :connectionId AND folderPath = :folderPath
    """,
  )
  suspend fun deleteByConnectionAndPath(
    connectionId: Long,
    folderPath: String,
  )
}
