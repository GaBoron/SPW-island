// SPDX-License-Identifier: GPL-3.0-only
#pragma once
#include <windows.h>
#include <d3d11.h>
#include <d3d12.h>
#include <dxgi1_2.h>
#include <winrt/base.h>
#include <memory>
#include <string>
#include <utility>

// Each allocation is written once. Publish only after its D3D11 copy fence completes;
// Skia may retain the D3D12 resource after the capture worker drops its own reference.
struct DesktopSharedTexture {
    winrt::com_ptr<ID3D11Texture2D> texture;
    winrt::com_ptr<ID3D12Resource> resource;

    static std::shared_ptr<DesktopSharedTexture> create(
        ID3D11Device* device, ID3D12Device* consumer, UINT width, UINT height) {
        if (!device || !consumer || !width || !height)
            throw winrt::hresult_error(E_INVALIDARG, L"Invalid shared desktop texture parameters");

        auto result = std::make_shared<DesktopSharedTexture>();
        D3D11_TEXTURE2D_DESC description{};
        description.Width = width;
        description.Height = height;
        description.MipLevels = 1;
        description.ArraySize = 1;
        description.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
        description.SampleDesc.Count = 1;
        description.Usage = D3D11_USAGE_DEFAULT;
        description.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
        description.MiscFlags = D3D11_RESOURCE_MISC_SHARED_NTHANDLE | D3D11_RESOURCE_MISC_SHARED;
        winrt::check_hresult(device->CreateTexture2D(&description, nullptr, result->texture.put()));

        auto sharedResource = result->texture.as<IDXGIResource1>();
        winrt::handle sharedHandle;
        winrt::check_hresult(sharedResource->CreateSharedHandle(nullptr,
            DXGI_SHARED_RESOURCE_READ | DXGI_SHARED_RESOURCE_WRITE, nullptr, sharedHandle.put()));
        winrt::check_hresult(consumer->OpenSharedHandle(sharedHandle.get(),
            __uuidof(ID3D12Resource), result->resource.put_void()));
        return result;
    }

    static winrt::com_ptr<IDXGIAdapter1> adapter(std::wstring const& exactName) {
        winrt::com_ptr<IDXGIFactory1> factory;
        winrt::check_hresult(CreateDXGIFactory1(__uuidof(IDXGIFactory1), factory.put_void()));
        winrt::com_ptr<IDXGIAdapter1> selected;
        for (UINT index = 0;; ++index) {
            winrt::com_ptr<IDXGIAdapter1> candidate;
            const HRESULT status = factory->EnumAdapters1(index, candidate.put());
            if (status == DXGI_ERROR_NOT_FOUND) break;
            winrt::check_hresult(status);
            DXGI_ADAPTER_DESC1 description{};
            winrt::check_hresult(candidate->GetDesc1(&description));
            if (exactName != description.Description) continue;
            if (selected)
                throw winrt::hresult_error(E_FAIL, L"The rendering adapter name is ambiguous");
            selected = std::move(candidate);
        }
        if (!selected)
            throw winrt::hresult_error(E_FAIL, L"The rendering adapter was not found");
        return selected;
    }
};
