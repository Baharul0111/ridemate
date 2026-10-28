package app.ridemate.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device copy of the Robolectric migration test: the same v1 rows, migrated by the hand-written migration on the
 * phone's real SQLite, validated against the exported schema (android/app/schemas as androidTest assets).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), RideMateDb::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun migrate1To2_validatesSchemaAndKeepsEveryRow() {
        helper.createDatabase(TEST_DB, 1).use { insertV1Rows(it) }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, Migrations.MIGRATION_1_2)

        for (table in listOf("session", "leg", "quote", "evidence", "action_outbox", "post_fingerprint", "classifier_cache")) {
            db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); assertEquals(table, 1, it.getInt(0)) }
        }
        db.query("SELECT price_min, source_frame_hash FROM quote").use {
            it.moveToFirst()
            assertEquals(110, it.getInt(0))
            assertTrue(it.isNull(1))
        }
        db.execSQL("INSERT INTO guardian_snapshot (session_id, json, updated_at_utc) VALUES ('s1', '{}', 5)")
        db.execSQL(
            "INSERT INTO recorder_post (package, channel_id, category, is_ongoing, notif_id, tag, title_masked, " +
                "text_masked, redacted, removal_reason, at_utc) VALUES ('com.ubercab', NULL, NULL, 0, 1, NULL, NULL, NULL, 1, 8, 9)",
        )
        db.close()
    }

    @Test
    fun appDatabaseOpensV1FileThroughTheMigration() = runBlocking {
        val name = "migration-open-test.db"
        context.deleteDatabase(name)
        helper.createDatabase(name, 1).use { insertV1Rows(it) }
        val db = androidx.room.Room.databaseBuilder(context, RideMateDb::class.java, name)
            .addMigrations(*RideMateDb.MIGRATIONS)
            .build()
        try {
            assertEquals(listOf(Provider.UBER, Provider.OLA), db.sessionDao().get("s1")!!.selectedApps)
            val leg = db.legDao().get("l1")!!
            assertEquals(RideState.ASSIGNED, leg.state)
            assertEquals(EtaSource.VERIFIED, leg.etaSource)
            assertNull(db.guardianSnapshotDao().get("s1"))
            assertEquals(1, db.quoteDao().count())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    private fun insertV1Rows(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO session (id, created_at_utc, selected_apps, vehicle_classes, include_ondc, status, kept_leg_id, " +
                "closed_at_utc, pickup_lat, pickup_lng, pickup_label, drop_lat, drop_lng, drop_label) VALUES " +
                "('s1', 1000, 'UBER,OLA', 'AUTO', 1, 'ACTIVE', NULL, NULL, 26.3, 91.0, 'Home', 26.1, 91.6, 'Airport')",
        )
        db.execSQL(
            "INSERT INTO leg (id, session_id, provider, state, generation, sbn_key, eta_min, eta_source, distance_text, " +
                "plate_hash, t_assigned_elapsed, fee_rule_id, kept, dismissed, last_event_at) VALUES " +
                "('l1', 's1', 'UBER', 'ASSIGNED', 3, 'k', 4, 'VERIFIED', '1.2 km', 'ab12', 500, 'uber', 1, 0, 1200)",
        )
        db.execSQL(
            "INSERT INTO quote (id, leg_id, vehicle_class, label_text, price_min, price_max, eta_min, seen_at_utc, status, " +
                "source_version_code) VALUES (1, 'l1', 'AUTO', 'Auto', 110, 130, 3, 1100, 'VERIFIED', 777)",
        )
        db.execSQL(
            "INSERT INTO evidence (id, leg_id, rung, source, at_utc, details_enum) VALUES " +
                "(1, 'l1', 'TEMPLATE', 'TEMPLATE', 1300, 'CANCELLED')",
        )
        db.execSQL(
            "INSERT INTO action_outbox (id, leg_id, kind, due_elapsed_ms, due_wall_ms, boot_id, attempts, status, payload, " +
                "created_at_utc) VALUES (1, 'l1', 'REMINDER', 30000, 31000, 'boot-1', 0, 'PENDING', NULL, 1000)",
        )
        db.execSQL(
            "INSERT INTO post_fingerprint (`key`, package_name, sbn_key, last_fingerprint, last_seen, session_id) VALUES " +
                "('com.ubercab|k', 'com.ubercab', 'k', 'fp', 1400, 's1')",
        )
        db.execSQL(
            "INSERT INTO classifier_cache (package_name, version_code, normalised_text, event, session_id, created_at_utc) " +
                "VALUES ('com.ubercab', 5, 'driver is on the way', 'assigned', 's1', 1500)",
        )
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
