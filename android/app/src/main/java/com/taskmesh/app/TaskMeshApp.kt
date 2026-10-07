package com.taskmesh.app

import android.app.Application

class TaskMeshApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
