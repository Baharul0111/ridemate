package app.ridemate.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Hand-written migrations. Each one must produce exactly the schema Room exports for the target version
 * (`android/app/schemas`); `MigrationTest` checks that with `MigrationTestHelper`, on the JVM and on a device.
 */
object Migrations {
    /**
     * 1 → 2: guardian snapshots, the owner Recorder log, and the frame hash a quote was read from.
     * Purely additive, so every v1 row is kept as is.
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `guardian_snapshot` (`session_id` TEXT NOT NULL, `json` TEXT NOT NULL, " +
                    "`updated_at_utc` INTEGER NOT NULL, PRIMARY KEY(`session_id`), FOREIGN KEY(`session_id`) " +
                    "REFERENCES `session`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `recorder_post` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`package` TEXT NOT NULL, `channel_id` TEXT, `category` TEXT, `is_ongoing` INTEGER NOT NULL, " +
                    "`notif_id` INTEGER NOT NULL, `tag` TEXT, `title_masked` TEXT, `text_masked` TEXT, " +
                    "`redacted` INTEGER NOT NULL, `removal_reason` INTEGER, `at_utc` INTEGER NOT NULL, `version_code` INTEGER)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_recorder_post_at_utc` ON `recorder_post` (`at_utc`)")
            db.execSQL("ALTER TABLE `quote` ADD COLUMN `source_frame_hash` TEXT")
        }
    }
}
