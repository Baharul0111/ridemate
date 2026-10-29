package app.ridemate.session

import android.app.AlarmManager
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowPowerManager

/**
 * Reminders and the +60 s check with the screen off (goals 12–13): while I8 holds the CPU stays awake (a bounded
 * partial wake lock) and a while-idle alarm backs up the next due time; both go as soon as I8 clears.
 */
@RunWith(AndroidJUnit4::class)
class ReminderWakeKeeperTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))

    @Test
    fun `I8 holds a partial wake lock and a while-idle alarm for the next due time, released when it clears`() {
        val keeper = ReminderWakeKeeper(context)
        val due = SystemClock.elapsedRealtime() + 30_000

        keeper.update(i8 = true, nextDueElapsedMs = due)
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertNotNull(lock)
        assertTrue(lock!!.isHeld)
        assertTrue(keeper.isHolding)
        assertEquals(ReminderWakeKeeper.TAG, shadowOf(lock).tag)
        val alarm = alarms.nextScheduledAlarm
        assertNotNull(alarm)
        assertEquals(due, alarm!!.triggerAtMs)
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.type)
        assertTrue("fires in Doze too", alarm.isAllowWhileIdle)

        // The next reminder moves the alarm; the lock stays one lock.
        keeper.update(i8 = true, nextDueElapsedMs = due + 60_000)
        assertEquals(due + 60_000, alarms.nextScheduledAlarm!!.triggerAtMs)
        assertTrue(lock.isHeld)

        keeper.update(i8 = false, nextDueElapsedMs = null)
        assertFalse(lock.isHeld)
        assertNull(alarms.nextScheduledAlarm)
        assertFalse(keeper.isHolding)
    }

    @Test
    fun `the backstop is an exact while-idle alarm where Android allows it without a permission, inexact otherwise`() {
        val due = SystemClock.elapsedRealtime() + 60_000
        try {
            ShadowAlarmManager.setCanScheduleExactAlarms(false)
            val keeper = ReminderWakeKeeper(context)
            keeper.update(i8 = true, nextDueElapsedMs = due)
            val inexact = alarms.nextScheduledAlarm!!
            assertTrue(inexact.isAllowWhileIdle)
            assertEquals(ShadowAlarmManager.WINDOW_HEURISTIC, inexact.windowLengthMs)
            keeper.release()

            ShadowAlarmManager.setCanScheduleExactAlarms(true)
            val exactKeeper = ReminderWakeKeeper(context)
            exactKeeper.update(i8 = true, nextDueElapsedMs = due)
            val exact = alarms.nextScheduledAlarm!!
            assertTrue("fires in Doze too", exact.isAllowWhileIdle)
            assertEquals(ShadowAlarmManager.WINDOW_EXACT, exact.windowLengthMs)
            assertEquals(due, exact.triggerAtMs)
            exactKeeper.release()
        } finally {
            ShadowAlarmManager.setCanScheduleExactAlarms(false)
        }
    }

    @Test
    fun `the alarm receiver re-arms the backstop for the next due time, so a stopped service still gets woken`() {
        val next = SystemClock.elapsedRealtime() + 120_000
        ReminderWakeKeeper.armBackstop(context, next)
        assertEquals(next, alarms.nextScheduledAlarm!!.triggerAtMs)
        // One alarm, replaced rather than added.
        ReminderWakeKeeper.armBackstop(context, next + 120_000)
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(next + 120_000, alarms.nextScheduledAlarm!!.triggerAtMs)
        ReminderWakeKeeper.armBackstop(context, null)
        assertNull(alarms.nextScheduledAlarm)
    }

    @Test
    fun `release is safe when nothing was held`() {
        val keeper = ReminderWakeKeeper(context)
        keeper.release()
        keeper.update(i8 = false, nextDueElapsedMs = null)
        assertFalse(keeper.isHolding)
    }
}
