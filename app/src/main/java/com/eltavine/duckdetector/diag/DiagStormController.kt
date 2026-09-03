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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import java.util.concurrent.Executors

/**
 * DIAGNOSTIC ONLY. Reproduces the isolated-process spawn storm.
 *
 * The wedge has only ever been observed while the app is rapidly spawning
 * isolated children that each churn their address space -- the shape of
 * `TeeGrantDomainGranteeService`, which spawns a fresh `duck_grant_domain_*`
 * instance roughly every 300 ms. `bindIsolatedService` with a unique instance
 * name is what forces a *new* process per bind rather than reusing one.
 *
 * Unlike the real thing this runs unconditionally -- no root detection, no
 * scan gating -- so the identical workload can be driven on any kernel.
 */
object DiagStormController {

    private const val TAG = "DuckDiag"

    @Volatile
    private var running = false

    fun isRunning(): Boolean = running

    fun start(
        context: Context,
        totalSeconds: Int,
        concurrent: Int,
        childDurationMs: Int,
        threadsPerChild: Int,
        goldOnly: Boolean,
        spawnIntervalMs: Long,
    ) {
        if (running) {
            Log.i(TAG, "storm already running; ignoring start")
            return
        }
        running = true

        val appContext = context.applicationContext
        val executor = Executors.newCachedThreadPool()

        Thread({
            val startedAt = System.currentTimeMillis()
            val deadline = startedAt + totalSeconds * 1000L
            var spawned = 0
            val live = ArrayDeque<ServiceConnection>()

            Log.i(
                TAG,
                "STORM START total=${totalSeconds}s concurrent=$concurrent " +
                    "childMs=$childDurationMs threads=$threadsPerChild gold=$goldOnly " +
                    "interval=${spawnIntervalMs}ms",
            )

            try {
                while (System.currentTimeMillis() < deadline) {
                    while (live.size >= concurrent) {
                        val oldest = live.removeFirst()
                        runCatching { appContext.unbindService(oldest) }
                    }

                    val connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit
                        override fun onServiceDisconnected(name: ComponentName?) = Unit
                    }

                    val intent = Intent(appContext, DiagChurnService::class.java).apply {
                        putExtra(DiagChurnService.EXTRA_DURATION_MS, childDurationMs)
                        putExtra(DiagChurnService.EXTRA_THREADS, threadsPerChild)
                        putExtra(DiagChurnService.EXTRA_GOLD_ONLY, goldOnly)
                    }

                    val bound = runCatching {
                        appContext.bindIsolatedService(
                            intent,
                            Context.BIND_AUTO_CREATE,
                            "duckdiag_churn_${System.nanoTime()}",
                            executor,
                            connection,
                        )
                    }.getOrDefault(false)

                    if (bound) {
                        live.addLast(connection)
                        spawned++
                    } else {
                        Log.w(TAG, "bindIsolatedService failed at spawn=$spawned")
                    }

                    /* Heartbeat: on a wedge the device dies mid-run, so the last
                     * line that reaches a host-side `adb logcat` bounds how far
                     * the storm got before the core stalled. */
                    val elapsed = (System.currentTimeMillis() - startedAt) / 1000
                    Log.i(TAG, "STORM t=${elapsed}s spawned=$spawned live=${live.size}")

                    Thread.sleep(spawnIntervalMs)
                }
            } catch (throwable: Throwable) {
                Log.w(TAG, "storm aborted: ${throwable.message}")
            } finally {
                while (live.isNotEmpty()) {
                    runCatching { appContext.unbindService(live.removeFirst()) }
                }
                val elapsed = (System.currentTimeMillis() - startedAt) / 1000
                Log.i(TAG, "STORM SURVIVED total_spawned=$spawned elapsed=${elapsed}s")
                running = false
                executor.shutdownNow()
            }
        }, "duckdiag-storm").apply {
            isDaemon = false
            start()
        }
    }
}
