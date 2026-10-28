package app.ridemate.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.EvidenceRung
import app.ridemate.core.model.Provider
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.RideState
import app.ridemate.core.model.VehicleClass
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room migrations on the JVM (Robolectric) with the exported schemas in android/app/schemas (wired as test assets).
 * The same test runs on a device under androidTest.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), RideMateDb::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `migrate 1 to 2 validates the schema and keeps every row`() {
        helper.createDatabase(TEST_DB, 1).use { insertV1Rows(it) }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, Migrations.MIGRATION_1_2)

        db.query("SELECT COUNT(*) FROM session").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("SELECT state, generation, plate_hash FROM leg WHERE id = 'l1'").use {
            it.moveToFirst()
            assertEquals("ASSIGNED", it.getString(0))
            assertEquals(3, it.getInt(1))
            assertEquals("ab12", it.getString(2))
        }
        db.query("SELECT price_min, price_max, source_frame_hash FROM quote").use {
            it.moveToFirst()
            assertEquals(110, it.getInt(0))
            assertEquals(130, it.getInt(1))
            assertEquals(true, it.isNull(2))
        }
        for (table in listOf("evidence", "action_outbox", "post_fingerprint", "classifier_cache")) {
            db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); assertEquals(table, 1, it.getInt(0)) }
        }
        // The new v2 tables work, including the cascade from session.
        db.execSQL("INSERT INTO guardian_snapshot (session_id, json, updated_at_utc) VALUES ('s1', '{}', 5)")
        db.execSQL(
            "INSERT INTO recorder_post (package, channel_id, category, is_ongoing, notif_id, tag, title_masked, " +
                "text_masked, redacted, removal_reason, at_utc) VALUES ('com.ubercab', 'trip', NULL, 1, 7, NULL, 't', 'x', 0, NULL, 9)",
        )
        db.execSQL("UPDATE quote SET source_frame_hash = 'f00d'")
        db.close()
    }

    @Test
    fun `the app database opens a v1 file through the migration without losing data`() = runBlocking {
        context.deleteDatabase(RideMateDb.NAME)
        helper.createDatabase(RideMateDb.NAME, 1).use { insertV1Rows(it) }

        val db = RideMateDb.build(context)
        try {
            val session = db.sessionDao().get("s1")!!
            assertEquals(listOf(Provider.UBER, Provider.OLA), session.selectedApps)
            assertEquals(listOf(VehicleClass.AUTO), session.vehicleClasses)
            assertEquals(SessionStatus.ACTIVE, session.status)
            val leg = db.legDao().get("l1")!!
            assertEquals(RideState.ASSIGNED, leg.state)
            assertEquals(EtaSource.VERIFIED, leg.etaSource)
            val quote = db.quoteDao().count()
            assertEquals(1, quote)
            assertEquals(EvidenceRung.TEMPLATE, db.evidenceDao().forLeg("l1").single().rung)
            assertEquals(OutboxKind.REMINDER, db.outboxDao().pending().single().kind)
            assertEquals("fp", db.postFingerprintDao().get("com.ubercab|k")!!.lastFingerprint)
            assertEquals("assigned", db.classifierCacheDao().get("com.ubercab", 5L, "driver is on the way")!!.event)
            assertNull(db.guardianSnapshotDao().get("s1"))
            assertEquals(0, db.recorderPostDao().count())
            db.guardianSnapshotDao().upsert(GuardianSnapshotEntity("s1", "{}", 1L))
            assertEquals(1, db.guardianSnapshotDao().count())
        } finally {
            db.close()
            context.deleteDatabase(RideMateDb.NAME)
        }
    }

    @Test
    fun `quote status and vehicle class survive as text`() {
        helper.createDatabase(TEST_DB, 1).use { insertV1Rows(it) }
        helper.runMigrationsAndValidate(TEST_DB, 2, true, Migrations.MIGRATION_1_2).use { db ->
            db.query("SELECT vehicle_class, status FROM quote").use {
                it.moveToFirst()
                assertEquals(VehicleClass.AUTO.name, it.getString(0))
                assertEquals(QuoteStatus.VERIFIED.name, it.getString(1))
            }
        }
    }

    companion object {
        const val TEST_DB = "migration-test.db"

        /** One row in every v1 table, written with v1 SQL only. */
        fun insertV1Rows(db: SupportSQLiteDatabase) {
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
    }
}
