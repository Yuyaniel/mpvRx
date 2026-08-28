/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.database.repository

import app.gyrolet.mpvrx.database.dao.WebDavFolderBookmarkDao
import app.gyrolet.mpvrx.database.entities.WebDavFolderBookmarkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Local persistence for WebDAV folder bookmarks.
 *
 * Bookmarks are intentionally independent from network connection rows: removing a
 * WebDAV storage source never removes its bookmarks.
 */
class WebDavFolderBookmarkRepository(
  private val dao: WebDavFolderBookmarkDao,
) {
  fun observeAll(): Flow<List<WebDavFolderBookmarkEntity>> = dao.observeAll()

  fun observeIsBookmarked(
    connectionId: Long,
    folderPath: String,
  ): Flow<Boolean> = dao.observeIsBookmarked(connectionId, folderPath)

  /**
   * Add a bookmark, or refresh its display snapshot when the same folder is
   * bookmarked again (e.g. the connection was renamed).
   */
  suspend fun add(bookmark: WebDavFolderBookmarkEntity) {
    withContext(Dispatchers.IO) {
      val existing = dao.findByConnectionAndPath(bookmark.connectionId, bookmark.folderPath)
      if (existing == null) {
        dao.upsert(bookmark.copy(id = 0))
      } else {
        dao.upsert(
          existing.copy(
            connectionName = bookmark.connectionName,
            folderName = bookmark.folderName,
          ),
        )
      }
    }
  }

  suspend fun remove(
    connectionId: Long,
    folderPath: String,
  ) {
    withContext(Dispatchers.IO) { dao.deleteByConnectionAndPath(connectionId, folderPath) }
  }

  suspend fun removeById(id: Long) {
    withContext(Dispatchers.IO) { dao.deleteById(id) }
  }
}
