package app.ridemate.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * RideMate's phone database (architecture §5). `exportSchema = true`: every version's schema is committed under
 * `android/app/schemas`, and every version step has a hand-written migration in [Migrations] that is tested with
 * `MigrationTestHelper`. There is no destructive fallback: a missing migration is a crash in testing, never a
 * silent wipe of someone's open ride.
 *
 * Version history:
 * - 1: session, leg, quote, evidence, action_outbox, post_fingerprint, classifier_cache.
 * - 2: + guardian_snapshot, + recorder_post, + quote.source_frame_hash.
 */
@Database(
    entities = [
        SessionEntity::class,
        LegEntity::class,
        QuoteEntity::class,
        EvidenceEntity::class,
        ActionOutboxEntity::class,
        PostFingerprintEntity::class,
        ClassifierCacheEntity::class,
        GuardianSnapshotEntity::class,
        RecorderPostEntity::class,
    ],
    version = RideMateDb.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class RideMateDb : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun legDao(): LegDao
    abstract fun quoteDao(): QuoteDao
    abstract fun evidenceDao(): EvidenceDao
    abstract fun outboxDao(): ActionOutboxDao
    abstract fun postFingerprintDao(): PostFingerprintDao
    abstract fun classifierCacheDao(): ClassifierCacheDao
    abstract fun guardianSnapshotDao(): GuardianSnapshotDao
    abstract fun recorderPostDao(): RecorderPostDao

    companion object {
        const val NAME = "ridemate.db"
        const val VERSION = 2

        /** Every migration, in order. */
        val MIGRATIONS = arrayOf(Migrations.MIGRATION_1_2)

        /** The on-disk database. */
        fun build(context: Context): RideMateDb =
            Room.databaseBuilder(context.applicationContext, RideMateDb::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()

        /** An in-memory database (tests, and the fallback when the disk database cannot be opened). */
        fun inMemory(context: Context): RideMateDb =
            Room.inMemoryDatabaseBuilder(context.applicationContext, RideMateDb::class.java).build()
    }
}
