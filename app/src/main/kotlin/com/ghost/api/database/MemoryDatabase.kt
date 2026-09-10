package com.ghost.api.database

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
        SemanticFactFts::class,
        AgentState::class,
        DiaryEntry::class
    ],
    version = 6,
    exportSchema = false  // Disable schema export to fix build warning
)
abstract class MemoryDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao
    abstract fun semanticFactDao(): SemanticFactDao
    abstract fun agentStateDao(): AgentStateDao
    abstract fun diaryDao(): DiaryDao

    companion object {
        @Volatile
        private var INSTANCE: MemoryDatabase? = null

        // === MIGRATIONS ===
        // Add new migrations here as schema evolves
        // Format: MIGRATION_X_Y migrates from version X to version Y

        /**
         * Migration 2→3: Added tokenHash column to conversations
         * This is a no-op if column already exists (safe re-run)
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Timber.i("MemoryDatabase: Migrating 2→3")
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
                Timber.i("MemoryDatabase: Migrating 3→4 (FTS)")
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `conversations_fts` USING FTS4(`userMessage`, `assistantResponse`, content=`conversations`)")
                // Triggers are handled by Room if using contentEntity
            }
        }

        /**
         * Migration 4→5: Added FTS table for semantic facts
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Timber.i("MemoryDatabase: Migrating 4→5 (SemanticFacts FTS)")
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `semantic_facts_fts` USING FTS4(`subject`, `predicate`, `object_`, content=`semantic_facts`)")
            }
        }

        /**
         * Migration 5→6: Added imageUri column to conversations
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Timber.i("MemoryDatabase: Migrating 5→6 (imageUri)")
                try {
                    db.execSQL("ALTER TABLE conversations ADD COLUMN imageUri TEXT DEFAULT NULL")
                } catch (e: Exception) {
                    Timber.d("Migration 5→6: imageUri column may already exist")
                }
            }
        }

        /**
         * Migration 1→2: Added diary_entries table
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Timber.i("MemoryDatabase: Migrating 1→2")
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

        fun getDatabase(context: Context): MemoryDatabase {
            return INSTANCE ?: synchronized(this) {
                val legacyDb = context.getDatabasePath("oracle_database")
                val dbName = if (legacyDb.exists()) "oracle_database" else "ghost_memory_database"

                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MemoryDatabase::class.java,
                    dbName
                )
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                // Apply migrations in order
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                // CRITICAL: If ANY migration fails, wipe the database rather than crash.
                // Data loss is acceptable vs bootloop.
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                Timber.i("MemoryDatabase: Initialized (version 6, file=$dbName)")
                instance
            }
        }
    }
}
