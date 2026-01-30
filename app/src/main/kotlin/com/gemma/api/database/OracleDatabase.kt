package com.gemma.api.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConversationTurn::class,
        // ConversationTurnFts::class,  // Disabled temporarily - may cause crashes
        SemanticFact::class,
        AgentState::class,
        DiaryEntry::class
    ],
    version = 3,  // Bump to force migration
    exportSchema = false
)
abstract class OracleDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao
    abstract fun semanticFactDao(): SemanticFactDao
    abstract fun agentStateDao(): AgentStateDao
    abstract fun diaryDao(): DiaryDao

    companion object {
        @Volatile
        private var INSTANCE: OracleDatabase? = null

        fun getDatabase(context: Context): OracleDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    OracleDatabase::class.java,
                    "oracle_database"
                )
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
