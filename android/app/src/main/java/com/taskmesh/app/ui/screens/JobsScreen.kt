@file:OptIn(ExperimentalMaterial3Api::class)

package com.taskmesh.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskmesh.app.ServiceLocator
import com.taskmesh.app.data.ApiErrors
import com.taskmesh.app.data.JobStatus
import com.taskmesh.app.data.ProjectDto
import com.taskmesh.app.data.SessionRepository
import com.taskmesh.app.model.StatusMapping
import com.taskmesh.app.ui.components.EmptyState
import com.taskmesh.app.ui.components.ErrorPanel
import com.taskmesh.app.ui.components.JobRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class JobsUiState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val jobs: List<com.taskmesh.app.data.JobDto> = emptyList(),
    val total: Long = 0L,
    val projects: List<ProjectDto> = emptyList(),
    val statusFilter: JobStatus? = null,
    val projectFilter: String? = null,
    val error: String? = null,
)

class JobsViewModel(
    private val session: SessionRepository = ServiceLocator.session,
    initialProjectId: String? = null,
) : ViewModel() {

    private val internal = MutableStateFlow(JobsUiState(projectFilter = initialProjectId))
    val uiState: StateFlow<JobsUiState> = internal.asStateFlow()

    private var nextPage = 0

    init {
        load()
    }

    fun load() {
        nextPage = 1
        viewModelScope.launch {
            val state = internal.value
            internal.update { it.copy(loading = it.jobs.isEmpty(), error = null) }
            session.projects(page = 0, size = 100).fold(
                onSuccess = { page ->
                    internal.update { it.copy(projects = page.items) }
                },
                onFailure = { /* project chips are optional chrome; the jobs call reports errors */ },
            )
            session.jobs(
                projectId = state.projectFilter,
                status = state.statusFilter,
                page = 0,
                size = PAGE_SIZE,
            ).fold(
                onSuccess = { page ->
                    internal.update {
                        it.copy(jobs = page.items, total = page.total, loading = false, error = null)
                    }
                },
                onFailure = { throwable ->
                    internal.update {
                        it.copy(loading = false, error = ApiErrors.message(throwable))
                    }
                },
            )
        }
    }

    fun refresh() {
        nextPage = 1
        viewModelScope.launch {
            val state = internal.value
            internal.update { it.copy(refreshing = true, error = null) }
            session.jobs(
                projectId = state.projectFilter,
                status = state.statusFilter,
                page = 0,
                size = PAGE_SIZE,
            ).fold(
                onSuccess = { page ->
                    internal.update {
                        it.copy(jobs = page.items, total = page.total, refreshing = false, error = null)
                    }
                },
                onFailure = { throwable ->
                    internal.update {
                        it.copy(refreshing = false, error = ApiErrors.message(throwable))
                    }
                },
            )
        }
    }

    fun loadMore() {
        val state = internal.value
        if (state.loading || state.jobs.size >= state.total) {
            return
        }
        viewModelScope.launch {
            session.jobs(
                projectId = state.projectFilter,
                status = state.statusFilter,
                page = nextPage,
                size = PAGE_SIZE,
            ).fold(
                onSuccess = { page ->
                    nextPage += 1
                    internal.update { it.copy(jobs = it.jobs + page.items, total = page.total) }
                },
                onFailure = { /* silent: pull-to-refresh or retry reports errors */ },
            )
        }
    }

    fun setStatusFilter(status: JobStatus?) {
        if (internal.value.statusFilter == status) {
            return
        }
        internal.update { it.copy(statusFilter = status) }
        load()
    }

    fun setProjectFilter(projectId: String?) {
        if (internal.value.projectFilter == projectId) {
            return
        }
        internal.update { it.copy(projectFilter = projectId) }
        load()
    }

    companion object {
        const val PAGE_SIZE = 50
    }
}

@Composable
fun JobsScreen(
    initialProject: String?,
    onJobClick: (String) -> Unit,
    onNewJob: () -> Unit,
) {
    val screenViewModel: JobsViewModel = viewModel { JobsViewModel(initialProject) }
    val state by screenViewModel.uiState.collectAsState()
    val projectNames = state.projects.associate { project -> project.id to project.name }

    LaunchedEffect(initialProject) {
        // Route argument changed (e.g. deep filter from Projects) after the
        // view model already existed: re-apply the filter.
        if (state.projectFilter != initialProject) {
            screenViewModel.setProjectFilter(initialProject)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                    text = "Jobs",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = screenViewModel::refresh) {
                    Icon(imageVector = Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(listOf<JobStatus?>(null) + JobStatus.entries) { status ->
                    FilterChip(
                        selected = state.statusFilter == status,
                        onClick = { screenViewModel.setStatusFilter(status) },
                        label = {
                            Text(text = status?.let { StatusMapping.label(it) } ?: "All statuses")
                        },
                    )
                }
            }

            if (state.projects.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(listOf<String?>(null) + state.projects.map { it.id }) { projectId ->
                        FilterChip(
                            selected = state.projectFilter == projectId,
                            onClick = { screenViewModel.setProjectFilter(projectId) },
                            label = {
                                Text(
                                    text = projectId?.let { projectNames[it] } ?: "All projects",
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
            }

            val error = state.error
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = screenViewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.loading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                    error != null && state.jobs.isEmpty() -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                    ) {
                        ErrorPanel(message = error, onRetry = screenViewModel::load)
                    }
                    state.jobs.isEmpty() -> EmptyState(
                        title = "No jobs",
                        subtitle = "Jobs you submit appear here. Tap + to create one.",
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.jobs, key = { job -> job.id }) { job ->
                            JobRow(job = job, onClick = { onJobClick(job.id) })
                        }
                        if (state.jobs.size < state.total) {
                            item(key = "load-more") {
                                TextButton(
                                    onClick = screenViewModel::loadMore,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(text = "Load more · ${state.jobs.size} of ${state.total}")
                                }
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onNewJob,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = "New job")
        }
    }
}
