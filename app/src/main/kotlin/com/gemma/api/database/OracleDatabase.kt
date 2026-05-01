package com.gemma.api.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import timber.log.Timber

@Database(
    entities = [
        ConversationTurn::class,
        ConversationTurnFts::class,
        SemanticFact::class,
        AgentState::class,
        DiaryEntry::class
    ],
    version = 4,
    exportSchema = false  // Disable schema export to fix build warning
)
abstract class OracleDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao
    abstract fun semanticFactDao(): SemanticFactDao
    abstract fun agentStateDao(): AgentStateDao
    abstract fun diaryDao(): DiaryDao

    companion object {
        @Volatile
        private var INSTANCE: OracleDatabase? = null

        // === MIGRATIONS ===
        // Add new migrations here as schema evolves
        // Format: MIGRATION_X_Y migrates from version X to version Y

        /**
         * Migration 2→3: Added tokenHash column to conversations
         * This is a no-op if column already exists (safe re-run)
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Timber.i("OracleDatabase: Migrating 2→3")
                try {
                    // Add tokenHash column if it doesn't exist
                    db.execSQL("ALTER TABLE conversations ADD COLUMN tokenHash TEXT NOT NULL DEFAULT ''")
                } catch (e: Exception) {
                    // Column may already exist - that's fine
                    Timber.d("Migration 2→3: tokenHash column may already exist")
                }
            }
        }

        /**
         * Migration 3→4: Added FTS table for conversations
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Timber.i("OracleDatabase: Migrating 3→4 (FTS)")
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `conversations_fts` USING FTS4(`userMessage`, `assistantResponse`, content=`conversations`)")
                // Triggers are handled by Room if using contentEntity
            }
        }

        /**
         * Migration 1→2: Added diary_entries table
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Timber.i("OracleDatabase: Migrating 1→2")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS diary_entries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        eventType TEXT NOT NULL,
                        observation TEXT NOT NULL,
                        contextData TEXT NOT NULL
                    )
                """)
            }
        }

        fun getDatabase(context: Context): OracleDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    OracleDatabase::class.java,
                    "oracle_database"
                )
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                // Apply migrations in order
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                // CRITICAL: If ANY migration fails, wipe the database rather than crash.
                // Data loss is acceptable vs bootloop.
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                Timber.i("OracleDatabase: Initialized (version 4)")
                instance
            }
        }
    }
}
