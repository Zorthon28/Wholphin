package com.github.damontecres.wholphin.ui.discover

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.api.seerr.infrastructure.ClientException
import com.github.damontecres.wholphin.api.seerr.infrastructure.ServerException
import com.github.damontecres.wholphin.api.seerr.SeerrApiClient
import com.github.damontecres.wholphin.services.SeerrConnectionStatus
import com.github.damontecres.wholphin.services.SeerrServerRepository
import com.github.damontecres.wholphin.services.SeerrService
import com.github.damontecres.wholphin.services.hilt.StandardOkHttpClient
import com.github.damontecres.wholphin.ui.setup.seerr.SeerrDialog
import com.github.damontecres.wholphin.ui.setup.seerr.SeerrDialogMode
import com.github.damontecres.wholphin.ui.setup.seerr.createSeerrApiUrl
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.userApi
import java.io.IOException
import javax.inject.Inject

internal data class TvConnectionCheck(
    @StringRes val jellyfin: Int,
    @StringRes val seerr: Int,
    val running: Boolean = false,
)

@HiltViewModel
class ExploreConnectionViewModel
    @Inject
    constructor(
        private val jellyfin: ApiClient,
        private val seerr: SeerrService,
        repository: SeerrServerRepository,
        @param:StandardOkHttpClient private val httpClient: OkHttpClient,
    ) : ViewModel() {
        val connection = repository.connection
        internal val check = MutableStateFlow<TvConnectionCheck?>(null)

        fun checkConnections() {
            if (check.value?.running == true) return
            check.value = TvConnectionCheck(R.string.tv_check_running, R.string.tv_check_running, running = true)
            viewModelScope.launch {
                val jellyfinResult = async { checkEndpoint { jellyfin.userApi.getCurrentUser().content } }
                val seerrResult = async {
                    when (val current = connection.value.connection) {
                        SeerrConnectionStatus.NotConfigured -> R.string.tv_check_seerr_not_added
                        is SeerrConnectionStatus.Error -> {
                            val probe =
                                checkEndpoint {
                                    SeerrApiClient(createSeerrApiUrl(current.server.url), null, httpClient).publicApi.statusGet()
                                }
                            if (probe == R.string.tv_check_ok) R.string.tv_check_sign_in else probe
                        }
                        is SeerrConnectionStatus.Success -> checkEndpoint { seerr.api.authApi.authMeGet() }
                    }
                }
                check.value = TvConnectionCheck(jellyfinResult.await(), seerrResult.await())
            }
        }

        @StringRes
        private suspend fun checkEndpoint(request: suspend () -> Unit): Int =
            withTimeoutOrNull(6000) {
                try {
                    request()
                    R.string.tv_check_ok
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    failureMessage(ex)
                }
            } ?: R.string.tv_check_timeout

        @StringRes
        private fun failureMessage(ex: Exception): Int =
            when (ex) {
                is ClientException -> if (ex.statusCode in setOf(401, 403)) R.string.tv_check_sign_in else R.string.tv_check_server_error
                is InvalidStatusException -> if (ex.status in setOf(401, 403)) R.string.tv_check_sign_in else R.string.tv_check_server_error
                is ServerException -> R.string.tv_check_server_error
                is IOException -> R.string.tv_check_unreachable
                else -> R.string.tv_check_server_error
            }
    }

@Composable
internal fun ExploreConnectionContent(
    modifier: Modifier,
    viewModel: ExploreConnectionViewModel,
    restoreFocus: Boolean = false,
) {
    val state by viewModel.connection.collectAsState()
    val result by viewModel.check.collectAsState()
    val connection = state.connection
    val connected = connection is SeerrConnectionStatus.Success
    val focus = remember { FocusRequester() }
    var dialog by remember { mutableStateOf<SeerrDialogMode>(SeerrDialogMode.None) }

    LaunchedEffect(restoreFocus, dialog) {
        if (restoreFocus && dialog == SeerrDialogMode.None) focus.tryRequestFocus()
    }

    Column(
        (if (connected) Modifier else modifier).padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(stringResource(if (connected) R.string.tv_connection_check else R.string.explore), style = MaterialTheme.typography.headlineMedium)
        if (!connected) {
            Text(
                stringResource(if (connection is SeerrConnectionStatus.Error) R.string.tv_explore_reconnect_message else R.string.tv_explore_connection_message),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!connected) {
                Button(
                    onClick = {
                        dialog = if (connection is SeerrConnectionStatus.Error && state.pluginProvided) {
                            SeerrDialogMode.Error(connection.server, connection.user, connection.ex)
                        } else {
                            SeerrDialogMode.Add
                        }
                    },
                    modifier = Modifier.focusRequester(focus),
                ) {
                    Text(stringResource(if (connection is SeerrConnectionStatus.Error) R.string.tv_reconnect_seerr else R.string.tv_connect_seerr))
                }
            }
            Button(
                onClick = viewModel::checkConnections,
                modifier = if (connected) modifier else Modifier,
            ) {
                Text(stringResource(R.string.tv_connection_check))
            }
        }
        result?.let {
            Text(stringResource(R.string.tv_connection_result, "Jellyfin", stringResource(it.jellyfin)))
            Text(stringResource(R.string.tv_connection_result, "Null-seerr", stringResource(it.seerr)))
        }
    }
    SeerrDialog(dialog, state, onDismissRequest = { dialog = SeerrDialogMode.None })
}
