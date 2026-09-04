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

/**
 * Test-branch switch. When on, every first-run gate is skipped so the app lands
 * straight on the detector shell, which creates all detector ViewModels eagerly
 * and therefore runs every check on launch.
 *
 * Skipped: the legal agreement screen (30 s countdown + arithmetic captcha +
 * scroll-to-bottom), the startup policy review (notification / live-update /
 * CRL-network / package-visibility prompts) and the alpha-build warning overlay.
 *
 * This exists so a kernel bisect run can be `am start` -> observe, with no human
 * in the loop and no run-to-run variation in which checks actually executed.
 * It must never be true on `master`.
 */
object DiagAutopilot {
    const val ENABLED = true
}
