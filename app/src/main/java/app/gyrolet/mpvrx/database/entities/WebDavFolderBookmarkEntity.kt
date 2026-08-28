/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.database.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user-bookmarked WebDAV folder shown in the Network tab.
 *
 * The row deliberately has NO foreign key into `network_connections`: deleting a WebDAV
 * storage source must keep its bookmarks so the user can re-create the source later.
 * [connectionName] is a snapshot used for display when the source no longer exists.
 */
@Entity(
  tableName = "webdav_folder_bookmarks",
  indices = [Index(value = ["connectionId", "folderPath"], unique = true)],
)
@Immutable
data class WebDavFolderBookmarkEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0,
  val connectionId: Long,
  val connectionName: String,
  val folderPath: String,
  val folderName: String,
  val createdAt: Long = System.currentTimeMillis(),
)
