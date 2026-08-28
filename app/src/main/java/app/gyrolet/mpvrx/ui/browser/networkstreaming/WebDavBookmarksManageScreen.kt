/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.networkstreaming

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.database.entities.WebDavFolderBookmarkEntity
import app.gyrolet.mpvrx.database.repository.WebDavFolderBookmarkRepository
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.states.EmptyState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/** Management screen for bookmarked WebDAV folders. */
@Serializable
object WebDavBookmarksManageScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val repository = koinInject<WebDavFolderBookmarkRepository>()
    val coroutineScope = rememberCoroutineScope()
    val bookmarks by repository.observeAll().collectAsState(initial = emptyList())
    val navigationBarHeight = app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight.current

    BackHandler {
      backstack.popSafely()
    }

    Scaffold(
      topBar = {
        BrowserTopBar(
          title = stringResource(R.string.ui_manage_webdav_favorite_folders),
          isInSelectionMode = false,
          selectedCount = 0,
          totalCount = bookmarks.size,
          onBackClick = { backstack.popSafely() },
          onCancelSelection = {},
          onSortClick = null,
          onSearchClick = null,
          onSettingsClick = null,
          onDeleteClick = null,
          onRenameClick = null,
          isSingleSelection = false,
          onInfoClick = null,
          onShareClick = null,
          onPlayClick = null,
          onSelectAll = null,
          onInvertSelection = null,
          onDeselectAll = null,
        )
      },
    ) { padding ->
      if (bookmarks.isEmpty()) {
        Box(
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding),
          contentAlignment = Alignment.Center,
        ) {
          EmptyState(
            icon = Icons.RoundedFilled.Star,
            title = stringResource(R.string.ui_webdav_favorite_folders),
            message = stringResource(R.string.ui_no_webdav_bookmarks_hint),
          )
        }
      } else {
        LazyColumn(
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding),
          contentPadding =
            PaddingValues(
              start = 16.dp,
              top = 12.dp,
              end = 16.dp,
              bottom = navigationBarHeight + 16.dp,
            ),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          items(bookmarks, key = { it.id }) { bookmark ->
            WebDavBookmarkRow(
              bookmark = bookmark,
              onClick = {
                backstack.add(
                  NetworkBrowserScreen(
                    connectionId = bookmark.connectionId,
                    connectionName = bookmark.connectionName,
                    currentPath = bookmark.folderPath,
                  ),
                )
              },
              onDelete = {
                coroutineScope.launch { repository.removeById(bookmark.id) }
              },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun WebDavBookmarkRow(
  bookmark: WebDavFolderBookmarkEntity,
  onClick: () -> Unit,
  onDelete: () -> Unit,
) {
  Card(
    modifier =
      Modifier
        .fillMaxWidth()
        .combinedClickable(onClick = onClick),
    shape = AppShapeScale.large,
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
      ),
  ) {
    Row(
      modifier = Modifier.padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier =
          Modifier
            .size(48.dp)
            .clip(AppShapeScale.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.RoundedFilled.Folder,
          contentDescription = stringResource(R.string.ui_folder),
          modifier = Modifier.size(32.dp),
          tint = MaterialTheme.colorScheme.secondary,
        )
      }
      Spacer(modifier = Modifier.width(16.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = bookmark.folderName,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = bookmark.connectionName,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = bookmark.folderPath,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      IconButton(onClick = onDelete) {
        Icon(
          imageVector = Icons.RoundedFilled.Delete,
          contentDescription = stringResource(R.string.delete),
          tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
          modifier = Modifier.size(20.dp),
        )
      }
    }
  }
}
