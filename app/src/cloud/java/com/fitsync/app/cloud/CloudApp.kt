package com.fitsync.app.cloud

import android.app.Application
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class CloudApp : Application() {
    val sessionStore: CloudSessionStore by lazy { CloudSessionStore(this) }
    val adController: CloudAdController by lazy { CloudAdController(this) }
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: CloudApp
            private set
    }
}
