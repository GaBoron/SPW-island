// SPDX-License-Identifier: GPL-3.0-only
#pragma once
#include <windows.h>
#include <d3d11_4.h>
#include <winrt/base.h>
#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <vector>
#include "DesktopSharedTexture.h"

// Only the capture worker touches the D3D context. Never wait for a just-submitted GPU copy.
class DesktopReadback {
    struct Slot {
        winrt::com_ptr<ID3D11Texture2D> texture;
        std::shared_ptr<DesktopSharedTexture> shared;
        RECT crop{};
        uint64_t sequence = 0;
        int64_t sourceTime = 0;
        bool pending = false;
    };
    std::array<Slot, 3> slots;
    uint64_t sequence = 0;
    winrt::com_ptr<ID3D11Fence> fence;
    winrt::com_ptr<ID3D11DeviceContext4> fencedContext;
    HANDLE completedEvent = nullptr;
    uint64_t armed = 0;
    uint64_t lastSignal = 0;
    winrt::com_ptr<ID3D12Device> consumer;
    HRESULT fenceFailure = S_OK;
public:
    struct Frame {
        std::vector<uint8_t> pixels;
        RECT crop{};
        int64_t sourceTime = 0;
        std::shared_ptr<DesktopSharedTexture> shared;
    };
    DesktopReadback(ID3D11Device* device, ID3D11DeviceContext* context) {
        winrt::com_ptr<ID3D11Device5> modern;
        if (FAILED(fenceFailure = device->QueryInterface(__uuidof(ID3D11Device5), modern.put_void())) ||
            FAILED(fenceFailure = context->QueryInterface(__uuidof(ID3D11DeviceContext4), fencedContext.put_void())) ||
            FAILED(fenceFailure = modern->CreateFence(0, D3D11_FENCE_FLAG_NONE, __uuidof(ID3D11Fence), fence.put_void()))) {
            fencedContext = nullptr; fence = nullptr; return;
        }
        completedEvent = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        if (!completedEvent) {
            fenceFailure = HRESULT_FROM_WIN32(GetLastError());
            fence = nullptr; fencedContext = nullptr;
        }
    }
    ~DesktopReadback() {
        // Destruction runs on the capture worker, never on the EDT. Keep a registered
        // event valid until the GPU has finished every copy that may still signal it.
        if (fence && fence->GetCompletedValue() < lastSignal &&
            SUCCEEDED(fence->SetEventOnCompletion(lastSignal, completedEvent))) {
            while (fence->GetCompletedValue() < lastSignal) WaitForSingleObject(completedEvent, INFINITE);
        }
        if (completedEvent) CloseHandle(completedEvent);
    }
    DesktopReadback(DesktopReadback const&) = delete;
    DesktopReadback& operator=(DesktopReadback const&) = delete;
    void reset() { for (auto& slot : slots) slot = {}; }
    bool shareWith(ID3D12Device* device) {
        if (!fence || !device) return false;
        consumer.copy_from(device); return true;
    }
    bool shared() const { return static_cast<bool>(consumer); }
    HRESULT sharingFailure() const { return fenceFailure; }
    void useCpu() { reset(); consumer = nullptr; }
    bool pending() const {
        return std::any_of(slots.begin(), slots.end(), [](auto const& slot) { return slot.pending; });
    }
    HANDLE completion() {
        if (!fence) return nullptr;
        uint64_t next = UINT64_MAX;
        for (auto const& slot : slots) if (slot.pending) next = std::min(next, slot.sequence);
        if (next == UINT64_MAX) return nullptr;
        if (next != armed) {
            winrt::check_hresult(fence->SetEventOnCompletion(next, completedEvent));
            armed = next;
        }
        return completedEvent;
    }
    bool submit(ID3D11Device* device, ID3D11DeviceContext* context, ID3D11Texture2D* source,
                RECT crop, POINT monitorOrigin, int64_t sourceTime) {
        auto free = std::find_if(slots.begin(), slots.end(), [](auto const& slot) { return !slot.pending; });
        if (free == slots.end()) return false; // Skip intermediate work; retry with the newest frame.
        const UINT width = crop.right - crop.left, height = crop.bottom - crop.top;
        const UINT capacityWidth = (width + 63) / 64 * 64, capacityHeight = (height + 63) / 64 * 64;
        D3D11_TEXTURE2D_DESC description{};
        if (free->texture) free->texture->GetDesc(&description);
        if (consumer) {
            // Never overwrite a texture already handed to Skia. Snapshot copy is asynchronous.
            free->shared = DesktopSharedTexture::create(device, consumer.get(), width, height);
            free->texture = free->shared->texture;
        } else if (!free->texture || description.Width != capacityWidth || description.Height != capacityHeight) {
            free->texture = nullptr;
            description = {};
            description.Width = capacityWidth; description.Height = capacityHeight;
            description.MipLevels = 1; description.ArraySize = 1;
            description.Format = DXGI_FORMAT_B8G8R8A8_UNORM; description.SampleDesc.Count = 1;
            description.Usage = D3D11_USAGE_STAGING; description.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
            winrt::check_hresult(device->CreateTexture2D(&description, nullptr, free->texture.put()));
        }
        const D3D11_BOX box{ static_cast<UINT>(crop.left - monitorOrigin.x),
            static_cast<UINT>(crop.top - monitorOrigin.y), 0,
            static_cast<UINT>(crop.right - monitorOrigin.x), static_cast<UINT>(crop.bottom - monitorOrigin.y), 1 };
        context->CopySubresourceRegion(free->texture.get(), 0, 0, 0, 0, source, 0, &box);
        free->sequence = ++sequence;
        if (fence) {
            winrt::check_hresult(fencedContext->Signal(fence.get(), sequence));
            lastSignal = sequence;
        }
        context->Flush(); // Submit without a CPU/GPU rendezvous; Map is polled below.
        free->crop = crop; free->sourceTime = sourceTime; free->pending = true;
        return true;
    }
    bool takeLatest(ID3D11DeviceContext* context, Frame& result) {
        std::array<Slot*, 3> ordered{ &slots[0], &slots[1], &slots[2] };
        std::sort(ordered.begin(), ordered.end(), [](auto a, auto b) { return a->sequence > b->sequence; });
        for (auto slot : ordered) {
            if (!slot->pending) continue;
            if (slot->shared) {
                const auto completed = fence->GetCompletedValue();
                if (completed == UINT64_MAX)
                    throw winrt::hresult_error(DXGI_ERROR_DEVICE_REMOVED, L"Desktop capture device was removed");
                if (completed < slot->sequence) continue;
                result.shared = std::move(slot->shared); result.pixels.clear();
                result.crop = slot->crop; result.sourceTime = slot->sourceTime;
                for (auto& previous : slots) if (previous.sequence <= slot->sequence) {
                    previous.pending = false; previous.shared.reset(); previous.texture = nullptr;
                }
                return true;
            }
            const size_t width = slot->crop.right - slot->crop.left, height = slot->crop.bottom - slot->crop.top;
            result.pixels.resize(width * height * 4);
            D3D11_MAPPED_SUBRESOURCE mapped{};
            const auto status = context->Map(slot->texture.get(), 0, D3D11_MAP_READ, D3D11_MAP_FLAG_DO_NOT_WAIT, &mapped);
            if (status == DXGI_ERROR_WAS_STILL_DRAWING) continue;
            winrt::check_hresult(status);
            for (size_t row = 0; row < height; ++row)
                std::memcpy(result.pixels.data() + row * width * 4,
                    static_cast<uint8_t*>(mapped.pData) + row * mapped.RowPitch, width * 4);
            context->Unmap(slot->texture.get(), 0);
            result.shared.reset();
            result.crop = slot->crop; result.sourceTime = slot->sourceTime;
            // Discard older images. Reuse stays ordered by the same immediate context.
            for (auto& previous : slots) if (previous.sequence <= slot->sequence) previous.pending = false;
            return true;
        }
        return false;
    }
};
