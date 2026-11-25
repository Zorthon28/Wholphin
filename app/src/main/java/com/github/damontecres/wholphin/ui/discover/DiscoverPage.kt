package com.github.damontecres.wholphin.ui.discover

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.SeerrConnectionStatus
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.components.TabDetails
import com.github.damontecres.wholphin.ui.components.TabbedPage
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.nav.NavDrawerItem
import com.github.damontecres.wholphin.util.DiscoverRequestType

@Composable
fun DiscoverPage(
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
    connectionViewModel: ExploreConnectionViewModel = hiltViewModel(),
) {
    val connection by connectionViewModel.connection.collectAsState()
    if (connection.connection !is SeerrConnectionStatus.Success) {
        ExploreConnectionContent(modifier, connectionViewModel, restoreFocus = true)
        return
    }
    val tabs =
        remember {
            listOf(
                TabDetails(R.string.discover),
                TabDetails(R.string.downloads),
                TabDetails(R.string.request),
                TabDetails(R.string.search),
                TabDetails(R.string.movies_title),
                TabDetails(R.string.tv_shows_title),
                TabDetails(R.string.tv_connection_check),
            )
        }
    var showHeader by rememberSaveable { mutableStateOf(true) }

    TabbedPage(
        itemId = NavDrawerItem.Discover.id,
        tabs = tabs,
        modifier = modifier,
        showTabs = showHeader,
    ) { tabIndex, tabDetails ->
        when (tabIndex) {
            // Discover
            0 -> {
                SeerrDiscoverPage(
                    preferences = preferences,
                    positionCallback = { showHeader = it.row < 1 },
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .focusRequester(tabDetails.contentFocusRequester),
                )
            }

            // Downloads / Queue
            1 -> {
                SeerrDownloadsPage(
                    focusRequesterOnEmpty = tabDetails.tabFocusRequester,
                    positionCallback = { columns, index -> showHeader = index < columns },
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .focusRequester(tabDetails.contentFocusRequester),
                )
            }

            // Requests
            2 -> {
                SeerrRequestsPage(
                    focusRequesterOnEmpty = tabDetails.tabFocusRequester,
                    positionCallback = { columns, index -> showHeader = index < columns },
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .focusRequester(tabDetails.contentFocusRequester),
                )
            }

            // Search
            3 -> {
                DiscoverSearchPage(
                    preferences = preferences,
                    positionCallback = { columns, index -> showHeader = index < columns },
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .focusRequester(tabDetails.contentFocusRequester),
                )
            }

            // Movies
            4 -> {
                DiscoverRequestGrid(
                    viewModelKey = "movies",
                    showTitle = false,
                    destination =
                        Destination.DiscoverMoreResult(
                            DiscoverRequestType.DISCOVER_MOVIES,
                            0,
                        ),
                    positionCallback = { columns, index -> showHeader = index < columns },
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .focusRequester(tabDetails.contentFocusRequester),
                )
            }

            // TV
            5 -> {
                DiscoverRequestGrid(
                    viewModelKey = "tv",
                    showTitle = false,
                    destination =
                        Destination.DiscoverMoreResult(
                            DiscoverRequestType.DISCOVER_TV,
                            0,
                        ),
                    positionCallback = { columns, index -> showHeader = index < columns },
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .focusRequester(tabDetails.contentFocusRequester),
                )
            }

            6 -> {
                ExploreConnectionContent(
                    Modifier.focusRequester(tabDetails.contentFocusRequester),
                    connectionViewModel,
                )
            }

            else -> {
                ErrorMessage("Invalid tab index $tabIndex", null)
            }
        }
    }
}
