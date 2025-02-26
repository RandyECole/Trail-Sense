package com.kylecorry.trail_sense.tools.battery.infrastructure

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kylecorry.andromeda.core.system.Wakelocks
import com.kylecorry.andromeda.core.tryOrNothing
import com.kylecorry.andromeda.jobs.IOneTimeTaskScheduler
import com.kylecorry.andromeda.jobs.OneTimeTaskSchedulerFactory
import com.kylecorry.trail_sense.tools.battery.infrastructure.commands.BatteryLogCommand
import java.time.Duration
import java.time.LocalDateTime

class BatteryLogWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val wakelock = Wakelocks.get(applicationContext, WAKELOCK_TAG)
        tryOrNothing {
            wakelock?.acquire(Duration.ofSeconds(15).toMillis())
        }
        Log.d(javaClass.simpleName, "Started")
        try {
            BatteryLogCommand(applicationContext).execute()
        } finally {
            scheduler(applicationContext).once(Duration.ofHours(1))
            Log.d(javaClass.simpleName, "Scheduled next run at ${LocalDateTime.now().plusHours(1)}")
            wakelock?.release()
        }
        return Result.success()
    }

    companion object {

        private const val WAKELOCK_TAG = "com.kylecorry.trail_sense.BatteryLogWorker:wakelock"

        fun scheduler(context: Context): IOneTimeTaskScheduler {
            return OneTimeTaskSchedulerFactory(context).deferrable(
                BatteryLogWorker::class.java,
                2739852
            )
        }
    }

}