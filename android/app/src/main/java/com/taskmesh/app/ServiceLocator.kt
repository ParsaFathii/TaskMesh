package com.taskmesh.app

import android.content.Context
import com.taskmesh.app.data.SessionRepository
import com.taskmesh.app.data.SharedPrefsTokenStore
import com.taskmesh.app.notifications.NotificationCenter
import com.taskmesh.app.notifications.NotificationHelper

/**
 * Minimal manual dependency container (no DI framework — the dependency set is
 * intentionally small). Initialized once from [TaskMeshApp].
 */
object ServiceLocator {

    @Volatile
    private var initialized = false

    lateinit var session: SessionRepository
        private set

    lateinit var notifications: NotificationCenter
        private set

    lateinit var notificationHelper: NotificationHelper
        private set

    fun init(context: Context) {
        if (initialized) {
            return
        }
        synchronized(this) {
            if (initialized) {
                return
            }
            val appContext = context.applicationContext
            val store = SharedPrefsTokenStore(appContext)
            notifications = NotificationCenter()
            session = SessionRepository(store)
            notificationHelper = NotificationHelper(appContext)
            notificationHelper.createChannel()
            notifications.setListener { event ->
                if (session.notificationsEnabled) {
                    notificationHelper.post(event)
                }
            }
            initialized = true
        }
    }
}
