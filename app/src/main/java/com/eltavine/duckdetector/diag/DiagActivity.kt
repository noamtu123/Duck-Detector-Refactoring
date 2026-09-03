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

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.TextView

/**
 * DIAGNOSTIC ONLY. Foreground host for the churn storm.
 *
 * The workload must run while the app is the *top* app: Android confines
 * background and isolated processes to the little (Silver) cluster via cpusets,
 * and the stall has only ever been observed on the big (Gold) cluster. Driving
 * the storm from a background broadcast therefore cannot reproduce it -- the
 * children are cpuset-barred from Gold and `sched_setaffinity` to cpu4-7 fails
 * with EINVAL.
 *
 * This activity exists purely to hold top-app state. It runs no scan, so the
 * storm is the only load on the device.
 *
 *   adb shell am start -n com.eltavine.duckdetector/.diag.DiagActivity \
 *       --ei secs 180 --ei concurrent 4 --ei child_ms 4000 \
 *       --ei threads 4 --ei interval_ms 300 --ez gold true
 */
class DiagActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val secs = intent?.getIntExtra("secs", 180) ?: 180
        val concurrent = intent?.getIntExtra("concurrent", 4) ?: 4
        val childMs = intent?.getIntExtra("child_ms", 4_000) ?: 4_000
        val threads = intent?.getIntExtra("threads", 4) ?: 4
        val intervalMs = (intent?.getIntExtra("interval_ms", 300) ?: 300).toLong()
        val gold = intent?.getBooleanExtra("gold", true) ?: true

        setContentView(
            TextView(this).apply {
                text = "DUCKDIAG CHURN STORM\n\nsecs=$secs concurrent=$concurrent\nchild_ms=$childMs threads=$threads\ninterval=${intervalMs}ms gold=$gold\n\n(diagnostic build - no scan running)"
                setBackgroundColor(Color.BLACK)
                setTextColor(Color.GREEN)
                textSize = 16f
            },
        )

        Log.i(TAG, "DiagActivity foreground; starting storm")
        DiagStormController.start(
            context = this,
            totalSeconds = secs,
            concurrent = concurrent,
            childDurationMs = childMs,
            threadsPerChild = threads,
            goldOnly = gold,
            spawnIntervalMs = intervalMs,
        )
    }

    companion object {
        private const val TAG = "DuckDiag"
    }
}
