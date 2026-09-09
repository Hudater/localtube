package dev.hudater.localtube

import android.app.Application
import androidx.work.Configuration
import dev.hudater.localtube.di.AppContainer
import dev.hudater.localtube.work.LocalTubeWorkerFactory

class LocalTubeApplication : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(LocalTubeWorkerFactory(container))
            .build()
}
