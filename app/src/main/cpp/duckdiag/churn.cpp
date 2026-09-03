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

/*
 * DIAGNOSTIC ONLY -- address-space churn reproducer.
 *
 * Replays the syscall mix measured on the wedging core of this device
 * (caymanslm / SDM845, Kryo 385 Gold) during a Duck Detector scan:
 *
 *     mprotect 119/200, rt_sigprocmask 20, mmap 11, prctl 8,
 *     munmap 5, madvise 4, clone 4, sigaltstack 3, futex 2
 *
 * i.e. a thread-creation loop driving an mprotect / TLB-invalidation storm.
 * The same workload run from a *shell* domain never reproduces the stall; the
 * uncontrolled variable is the SELinux domain, so this payload is built into
 * the APK and executed from an isolated app-zygote child process.
 *
 * Optionally pins to the Gold cluster (cpu4-7), which is the only cluster the
 * stall has ever been observed on.
 */

#include <jni.h>

#include <android/log.h>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>
#include <vector>

#define DUCKDIAG_TAG "DuckDiag"
#define DDLOG(...) __android_log_print(ANDROID_LOG_INFO, DUCKDIAG_TAG, __VA_ARGS__)

namespace {

    constexpr size_t kPageSize = 4096;
    constexpr size_t kPagesPerSlot = 64;
    constexpr size_t kSlotBytes = kPagesPerSlot * kPageSize;
    constexpr int kSlots = 8;

    struct ChurnArgs {
        int64_t deadline_ms;
        int index;
        bool gold_only;
        unsigned long iterations;
    };

    int64_t now_ms() {
        struct timespec ts{};
        clock_gettime(CLOCK_MONOTONIC, &ts);
        return static_cast<int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
    }

    /* Render the CPUs a task is actually permitted to run on. Android confines
     * background and isolated processes to the little cluster with cpusets, so
     * this is the difference between a test that can reach the Gold cores and
     * one that silently cannot. */
    void log_allowed_cpus(const char *what) {
        cpu_set_t mask;
        CPU_ZERO(&mask);
        if (sched_getaffinity(0, sizeof(mask), &mask) != 0) {
            DDLOG("%s: sched_getaffinity failed: %s", what, strerror(errno));
            return;
        }
        char buf[64];
        int off = 0;
        for (int cpu = 0; cpu < 8; ++cpu) {
            if (CPU_ISSET(cpu, &mask)) {
                off += snprintf(buf + off, sizeof(buf) - off, "%d", cpu);
            }
        }
        buf[off] = '\0';
        DDLOG("%s: allowed_cpus=[%s]", what, buf);
    }

    /* Confine to the Gold cluster (cpu4-7). Fails with EINVAL when the cpuset
     * already excludes those cores -- which is the signal that this process can
     * never reproduce the stall, so say so loudly rather than churn uselessly. */
    bool pin_to_gold() {
        cpu_set_t mask;
        CPU_ZERO(&mask);
        for (int cpu = 4; cpu < 8; ++cpu) {
            CPU_SET(cpu, &mask);
        }
        if (sched_setaffinity(0, sizeof(mask), &mask) != 0) {
            DDLOG("pin_to_gold FAILED (%s) -- cpuset bars the Gold cluster; "
                  "this process cannot reproduce the stall", strerror(errno));
            log_allowed_cpus("pin_to_gold");
            return false;
        }
        return true;
    }

    /* One churn thread: mmap -> fault -> mprotect RO/RW toggling -> madvise ->
     * mremap -> munmap, with the signal-mask / prctl traffic the real workload
     * carries. Every mprotect is a page-table permission rewrite that forces
     * TLB invalidation, which is the pressure the stall needs. */
    void *churn_thread(void *raw) {
        auto *args = static_cast<ChurnArgs *>(raw);

        prctl(PR_SET_NAME, "duckdiag_churn", 0, 0, 0);
        if (args->index == 0) {
            log_allowed_cpus("churn_thread");
        }
        if (args->gold_only) {
            pin_to_gold();
        }

        void *slots[kSlots];
        for (auto &slot: slots) {
            slot = mmap(nullptr, kSlotBytes, PROT_READ | PROT_WRITE,
                        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
            if (slot == MAP_FAILED) {
                slot = nullptr;
            }
        }

        unsigned long iterations = 0;
        int cursor = 0;
        sigset_t sigs;
        sigemptyset(&sigs);
        sigaddset(&sigs, SIGUSR1);

        while (now_ms() < args->deadline_ms) {
            void *slot = slots[cursor % kSlots];
            cursor++;

            if (slot == nullptr) {
                slots[(cursor - 1) % kSlots] =
                        mmap(nullptr, kSlotBytes, PROT_READ | PROT_WRITE,
                             MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
                continue;
            }

            /* Fault the pages in so the PTEs are populated and young. */
            auto *bytes = static_cast<volatile unsigned char *>(slot);
            for (size_t page = 0; page < kPagesPerSlot; ++page) {
                bytes[page * kPageSize] = static_cast<unsigned char>(iterations);
            }

            /* The dominant operation: flip permissions back and forth across
             * sub-ranges, so each call splits/merges VMAs and invalidates. */
            for (size_t page = 0; page < kPagesPerSlot; page += 4) {
                void *addr = static_cast<char *>(slot) + page * kPageSize;
                mprotect(addr, 2 * kPageSize, PROT_READ);
                mprotect(addr, 2 * kPageSize, PROT_READ | PROT_WRITE);
            }

            pthread_sigmask(SIG_BLOCK, &sigs, nullptr);
            pthread_sigmask(SIG_UNBLOCK, &sigs, nullptr);

            madvise(slot, kSlotBytes, MADV_DONTNEED);

            /* Occasionally tear a slot down and rebuild it, exercising VMA
             * teardown and fresh address-space allocation. */
            if ((iterations & 0x0f) == 0) {
                munmap(slot, kSlotBytes);
                slots[(cursor - 1) % kSlots] =
                        mmap(nullptr, kSlotBytes, PROT_READ | PROT_WRITE,
                             MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
            }

            iterations++;
            if ((iterations & 0x1f) == 0) {
                DDLOG("churn t%d cpu=%d iterations=%lu",
                      args->index, sched_getcpu(), iterations);
            }
        }

        for (auto &slot: slots) {
            if (slot != nullptr) {
                munmap(slot, kSlotBytes);
            }
        }

        args->iterations = iterations;
        return nullptr;
    }

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_eltavine_duckdetector_diag_DiagNative_nativeRunChurn(
        JNIEnv *env,
        jobject /* this */,
        jint duration_ms,
        jint threads,
        jboolean gold_only
) {
    (void) env;

    if (threads < 1) {
        threads = 1;
    }
    if (threads > 16) {
        threads = 16;
    }

    const int64_t deadline = now_ms() + (duration_ms > 0 ? duration_ms : 0);

    DDLOG("churn START pid=%d duration_ms=%d threads=%d gold_only=%d",
          getpid(), duration_ms, threads, gold_only ? 1 : 0);

    std::vector<ChurnArgs> args(static_cast<size_t>(threads));
    std::vector<pthread_t> tids(static_cast<size_t>(threads));

    /* clone() traffic: each thread creation is a clone(CLONE_VM|...) sharing
     * one mm, so every mprotect below broadcasts a TLB shootdown to the other
     * threads' cores -- the cross-core pressure a single-threaded loop misses. */
    for (int i = 0; i < threads; ++i) {
        args[i].deadline_ms = deadline;
        args[i].index = i;
        args[i].gold_only = gold_only == JNI_TRUE;
        args[i].iterations = 0;
        if (pthread_create(&tids[i], nullptr, churn_thread, &args[i]) != 0) {
            tids[i] = 0;
        }
    }

    unsigned long total = 0;
    for (int i = 0; i < threads; ++i) {
        if (tids[i] != 0) {
            pthread_join(tids[i], nullptr);
            total += args[i].iterations;
        }
    }

    DDLOG("churn DONE pid=%d total_iterations=%lu", getpid(), total);
    return static_cast<jlong>(total);
}
