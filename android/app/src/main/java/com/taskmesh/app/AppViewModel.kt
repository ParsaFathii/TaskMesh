package com.taskmesh.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskmesh.app.data.SessionRepository
import com.taskmesh.app.data.SessionState
import com.taskmesh.app.data.isOperator
import com.taskmesh.app.notifications.NotificationCenter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Activity-scoped view model that drives the 30-second foreground poll feeding
 * the [NotificationCenter].
 *
 * Notification design (documented decision, see README.md): the app detects
 * events by polling the jobs the user owns (plus workers for ADMIN/OPERATOR)
 * while it is open; detected events are posted as system notifications. There
 * is deliberately no FCM/push dependency — full background push would require
 * a server push service which TaskMesh 0.1.0 does not bundle.
 */
class AppViewModel(
    private val session: SessionRepository = ServiceLocator.session,
    val notifications: NotificationCenter = ServiceLocator.notifications,
) : ViewModel() {

    val sessionState: StateFlow<SessionState> = session.sessionState

    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            sessionState.collect { state ->
                when (state) {
                    is SessionState.LoggedIn -> startPolling()
                    is SessionState.LoggedOut -> stopPolling()
                }
            }
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) {
            return
        }
        pollJob = viewModelScope.launch {
            while (isActive) {
                pollOnce()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun pollOnce() {
        val user = (sessionState.value as? SessionState.LoggedIn)?.user ?: return
        session.jobs(page = 0, size = POLL_PAGE_SIZE)
            .onSuccess { page -> notifications.observeJobs(page.items) }
        if (user.isOperator) {
            session.workers()
                .onSuccess { workers -> notifications.observeWorkers(workers) }
        }
    }

    companion object {
        const val POLL_INTERVAL_MS = 30_000L
        const val POLL_PAGE_SIZE = 100
    }
}
