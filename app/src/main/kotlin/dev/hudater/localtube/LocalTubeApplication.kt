package dev.hudater.localtube

import android.app.Application
import androidx.work.Configuration
import coil.ImageLoader
import dev.hudater.localtube.di.AppContainer
import dev.hudater.localtube.work.LocalTubeWorkerFactory

class LocalTubeApplication : Application(), Configuration.Provider, coil.ImageLoaderFactory {

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

    // Install the app-wide Coil loader with the localtube:// fetcher —
    // without this, AsyncImage falls back to Coil's default loader which
    // can't read thumbnails from LOCAL/SMB sources and renders them blank.
    override fun newImageLoader(): ImageLoader = container.imageLoader
}
