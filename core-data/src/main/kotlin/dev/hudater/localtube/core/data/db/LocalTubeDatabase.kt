package dev.hudater.localtube.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SourceEntity::class,
        ChannelEntity::class,
        PlaylistEntity::class,
        VideoEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class LocalTubeDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun channelDao(): ChannelDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun videoDao(): VideoDao

    companion object {
        private const val DB_NAME = "localtube.db"

        @Volatile
        private var instance: LocalTubeDatabase? = null

        fun getInstance(context: Context): LocalTubeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LocalTubeDatabase::class.java,
                    DB_NAME,
                ).build().also { instance = it }
            }
    }
}
