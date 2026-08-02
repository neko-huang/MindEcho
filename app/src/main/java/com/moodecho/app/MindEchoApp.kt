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
    }

    companion object {
        @Volatile
        private lateinit var instance: MindEchoApp

        /** Get the application instance for accessing global resources */
        fun getInstance(): MindEchoApp = instance
    }
}
