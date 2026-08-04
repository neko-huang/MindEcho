package com.moodecho.app

import android.app.Application
import com.moodecho.app.data.db.AppDatabase

/**
 * Application class for MindEcho.
 * Initializes core components such as the Room database.
 */
class MindEchoApp : Application() {

    // Lazily initialize the Room database instance
    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        isInitialized = true
    }

    companion object {
        @Volatile
        private lateinit var instance: MindEchoApp

        @Volatile
        private var isInitialized: Boolean = false

        /** Get the application instance for accessing global resources */
        fun getInstance(): MindEchoApp {
            check(isInitialized) { "MindEchoApp has not been initialized yet. Make sure to call onCreate() first." }
            return instance
        }
    }
}
