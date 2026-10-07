@file:OptIn(ExperimentalMaterial3Api::class)

package com.taskmesh.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskmesh.app.ServiceLocator
import com.taskmesh.app.data.ApiErrors
import com.taskmesh.app.data.SessionRepository
import com.taskmesh.app.data.WorkerDto
import com.taskmesh.app.ui.components.EmptyState
import com.taskmesh.app.ui.components.ErrorPanel
import com.taskmesh.app.ui.components.WorkerCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class WorkersUiState(
    val loading: Boolean = true,
    val workers: List<WorkerDto> = emptyList(),
    val error: String? = null,
)

class WorkersViewModel(
    private val session: SessionRepository = ServiceLocator.session,
) : ViewModel() {

    private val internal = MutableStateFlow(WorkersUiState())
    val uiState: StateFlow<WorkersUiState> = internal.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            internal.update { it.copy(loading = it.workers.isEmpty(), error = null) }
            session.workers().fold(
                onSuccess = { workers ->
                    internal.update { it.copy(workers = workers, loading = false, error = null) }
                },
                onFailure = { throwable ->
                    internal.update { it.copy(loading = false, error = ApiErrors.message(throwable)) }
                },
            )
        }
    }
}

@Composable
fun WorkersScreen() {
    val screenViewModel: WorkersViewModel = viewModel { WorkersViewModel() }
    val state by screenViewModel.uiState.collectAsState()

    // Worker health benefits from live updates while the screen is visible.
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(15_000)
            screenViewModel.load()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Workers",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = screenViewModel::load) {
                Icon(imageVector = Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        }

        val error = state.error
        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            error != null && state.workers.isEmpty() -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                ErrorPanel(message = error, onRetry = screenViewModel::load)
            }
            state.workers.isEmpty() -> EmptyState(
                title = "No workers",
                subtitle = "Registered workers appear here with live heartbeat status.",
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 320.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 96.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.workers, key = { worker -> worker.id }) { worker ->
                    WorkerCard(worker = worker)
                }
            }
        }
    }
}
