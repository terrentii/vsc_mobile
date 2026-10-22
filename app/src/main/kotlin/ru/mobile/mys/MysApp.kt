package ru.mobile.mys

import android.app.Application

class MysApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        Notifications.init(this)
    }
}
