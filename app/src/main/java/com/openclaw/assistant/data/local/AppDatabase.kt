package com.openclaw.assistant.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.openclaw.assistant.data.local.dao.ChatDao
import com.openclaw.assistant.data.local.entity.MessageEntity
import com.openclaw.assistant.data.local.entity.SessionEntity

@Database(entities = [SessionEntity::class, MessageEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "openclaw_database"
                )
                .fallbackToDestructiveMigration() // Simple strategy for now
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
