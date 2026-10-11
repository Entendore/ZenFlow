package com.example.zenflow

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.zenflow.util.CleanPrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderWorkerTest {

    @get:Rule val clean = CleanPrefsRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun runWorker(): ListenableWorker.Result =
        TestListenableWorkerBuilder<ReminderWorker>(ctx).build().doWork()

    private fun postedCount(): Int {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return Shadows.shadowOf(nm).allPostedNotifications.size
    }

    @Test
    fun `posts exactly one notification when permission is granted`() {
        Shadows.shadowOf(ctx.applicationContext as Application)
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(runWorker() is ListenableWorker.Result.Success)
        assertEquals(1, postedCount())
    }

    @Test
    fun `succeeds silently when permission is denied`() {
        assertTrue(runWorker() is ListenableWorker.Result.Success)
        assertEquals(0, postedCount())
    }

    @Test
    fun `schedule enqueues unique periodic work and cancel removes it`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ctx, Configuration.Builder().build()
        )
        val wm = WorkManager.getInstance(ctx)

        ReminderWorker.schedule(ctx, 8, 0)
        val infos = wm.getWorkInfosForUniqueWork(ReminderWorker.WORK_NAME).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos[0].state)

        ReminderWorker.cancel(ctx)
        val after = wm.getWorkInfosForUniqueWork(ReminderWorker.WORK_NAME).get()
        assertEquals(WorkInfo.State.CANCELLED, after[0].state)
    }
}
