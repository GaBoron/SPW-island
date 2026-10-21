// SPDX-License-Identifier: GPL-3.0-only
#pragma once
#include <windows.h>
#include <algorithm>
#include <chrono>
#include <deque>
#include <mutex>
#include <string>
#include <utility>
#include <cstdio>
#include <cstring>

// Per-capture bounded queue. Drained by the existing JVM listener, never by the render callback.
class CaptureDiagnostics {
    std::mutex mutex;
    std::deque<std::string> messages;
    unsigned long lost = 0;
public:
    void record(char const* stage, HRESULT code) noexcept {
        try {
            char prefix[512]{};
            const auto time = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::system_clock::now().time_since_epoch()).count();
            std::snprintf(prefix, sizeof(prefix), "%s HRESULT=0x%08lX thread=%lu timeMs=%lld", stage,
                static_cast<unsigned long>(code), GetCurrentThreadId(), static_cast<long long>(time));
            std::string message(prefix);
            void* stack[12]{};
            const auto count = CaptureStackBackTrace(1, 12, stack, nullptr);
            for (USHORT index = 0; index < count; ++index) {
                char frame[32]{};
                std::snprintf(frame, sizeof(frame), " %p", stack[index]);
                message += frame;
            }
            std::lock_guard lock(mutex);
            if (messages.size() == 64) { ++lost; return; }
            messages.push_back(std::move(message));
        } catch (...) { /* Diagnostics cannot terminate the native capture worker. */ }
    }
    int take(char* buffer, int capacity) noexcept {
        try {
            std::lock_guard lock(mutex);
            if (!buffer || capacity <= 0) return 0;
            if (lost) {
                std::snprintf(buffer, capacity, "native diagnostic queue lost=%lu", lost);
                lost = 0;
                return 1;
            }
            if (messages.empty()) { buffer[0] = 0; return 0; }
            const auto count = (std::min)(messages.front().size(), static_cast<size_t>(capacity - 1));
            std::memcpy(buffer, messages.front().data(), count); buffer[count] = 0;
            messages.pop_front();
            return 1;
        } catch (...) { return 0; }
    }
};
