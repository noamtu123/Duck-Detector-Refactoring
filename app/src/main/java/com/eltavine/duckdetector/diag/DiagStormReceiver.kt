/*
 * Copyright 2026 Duck Apps Contributor
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.eltavine.duckdetector.diag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * DIAGNOSTIC ONLY. adb entry point for the churn storm.
 *
 *   adb shell am broadcast -n com.eltavine.duckdetector/.diag.DiagStormReceiver \
 *       -a com.eltavine.duckdetector.DIAG_STORM \
 *       --ei secs 180 --ei concurrent 4 --ei child_ms 4000 \
 *       --ei threads 4 --ei interval_ms 300 --ez gold true
 *
 * Every argument is optional; the defaults are the measured shape of the
 * workload that reproduces on this device.
 */
class DiagStormReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val secs = intent.getIntExtra("secs", 180)
        val concurrent = intent.getIntExtra("concurrent", 4)
        val childMs = intent.getIntExtra("child_ms", 4_000)
        val threads = intent.getIntExtra("threads", 4)
        val intervalMs = intent.getIntExtra("interval_ms", 300).toLong()
        val gold = intent.getBooleanExtra("gold", true)

        Log.i(TAG, "DIAG_STORM received: secs=$secs concurrent=$concurrent child_ms=$childMs threads=$threads interval_ms=$intervalMs gold=$gold")

        /* goAsync keeps this process at a live broadcast priority while the
         * storm thread gets going; the storm itself outlives the receiver. */
        val pending = goAsync()
        try {
            DiagStormController.start(
                context = context,
                totalSeconds = secs,
                concurrent = concurrent,
                childDurationMs = childMs,
                threadsPerChild = threads,
                goldOnly = gold,
                spawnIntervalMs = intervalMs,
            )
        } finally {
            pending.finish()
        }
    }

    companion object {
        private const val TAG = "DuckDiag"
    }
}
