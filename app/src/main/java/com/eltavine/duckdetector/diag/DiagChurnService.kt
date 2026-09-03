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

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log

/**
 * DIAGNOSTIC ONLY. Runs the churn payload inside an isolated child process.
 *
 * Declared `isolatedProcess=true` so each bound instance lands in the same
 * sandboxed app domain the wedging processes run in. The payload starts as
 * soon as the child is bound, because the point of the test is the workload,
 * not the binder round-trip.
 */
class DiagChurnService : Service() {

    private val binder = Binder()

    override fun onBind(intent: Intent?): IBinder {
        val durationMs = intent?.getIntExtra(EXTRA_DURATION_MS, DEFAULT_DURATION_MS)
            ?: DEFAULT_DURATION_MS
        val threads = intent?.getIntExtra(EXTRA_THREADS, DEFAULT_THREADS) ?: DEFAULT_THREADS
        val goldOnly = intent?.getBooleanExtra(EXTRA_GOLD_ONLY, true) ?: true

        Log.i(TAG, "child bound pid=${android.os.Process.myPid()} duration=$durationMs threads=$threads gold=$goldOnly")

        Thread({
            val iterations = DiagNative().runChurn(durationMs, threads, goldOnly)
            Log.i(TAG, "child finished pid=${android.os.Process.myPid()} iterations=$iterations")
        }, "duckdiag-churn").apply {
            isDaemon = true
            start()
        }

        return binder
    }

    companion object {
        const val TAG = "DuckDiag"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_THREADS = "threads"
        const val EXTRA_GOLD_ONLY = "gold_only"
        const val DEFAULT_DURATION_MS = 4_000
        const val DEFAULT_THREADS = 4
    }
}
