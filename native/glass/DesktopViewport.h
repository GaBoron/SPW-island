// SPDX-License-Identifier: GPL-3.0-only
#pragma once
#include <windows.h>
#include <algorithm>
#include <cstdint>
#include <cmath>

// Screen-space cache with hysteresis. Moving the island within it only changes shader UVs.
class DesktopViewport {
    RECT cached{};
    static LONG floorTile(LONG value) { return static_cast<LONG>(std::floor(value / 64.0) * 64); }
    static LONG ceilTile(LONG value) { return static_cast<LONG>(std::ceil(value / 64.0) * 64); }
public:
    void reset() { cached = {}; }
    RECT select(RECT required, RECT display) {
        RECT visible{};
        IntersectRect(&visible, &required, &display);
        required = visible;
        if (IsRectEmpty(&required)) return required;
        // Start replacing a tile while the old one still covers the required optical footprint.
        const bool contained = !IsRectEmpty(&cached) &&
            required.left >= cached.left + (cached.left > display.left ? 32 : 0) &&
            required.top >= cached.top + (cached.top > display.top ? 16 : 0) &&
            required.right <= cached.right - (cached.right < display.right ? 32 : 0) &&
            required.bottom <= cached.bottom - (cached.bottom < display.bottom ? 16 : 0);
        if (contained) return cached;
        RECT next{ std::max(display.left, floorTile(required.left - 128)),
            std::max(display.top, floorTile(required.top - 64)),
            std::min(display.right, ceilTile(required.right + 128)),
            std::min(display.bottom, ceilTile(required.bottom + 64)) };
        const auto width = next.right - next.left, height = next.bottom - next.top;
        if (width > 16384 || height > 16384 || static_cast<uint64_t>(width) * height * 4 > 64'000'000)
            next = required;
        cached = next;
        return cached;
    }
};
