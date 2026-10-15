// SPDX-License-Identifier: GPL-3.0-only
// Windows Graphics Capture transport. No desktop frames are saved to disk.
#include <windows.h>
#include <d3d11.h>
#include <dxgi1_6.h>
#include <windows.graphics.capture.interop.h>
#include <windows.graphics.directx.direct3d11.interop.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Metadata.h>
#include <winrt/Windows.Graphics.Capture.h>
#include <winrt/Windows.Graphics.DirectX.Direct3D11.h>
#include <winrt/Windows.Security.Authorization.AppCapabilityAccess.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <map>
#include <set>
#include <mutex>
#include <memory>
#include <thread>
#include <cstdio>
#include <vector>
#include "DesktopReadback.h"
#include "DesktopViewport.h"

using namespace winrt;
using namespace winrt::Windows::Graphics::Capture;
using namespace winrt::Windows::Graphics::DirectX;
using namespace winrt::Windows::Graphics::DirectX::Direct3D11;
using winrt::Windows::Foundation::Metadata::ApiInformation;
using winrt::Windows::Security::Authorization::AppCapabilityAccess::AppCapabilityAccessStatus;

namespace {
struct DpiScope {
    DPI_AWARENESS_CONTEXT previous = SetThreadDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    ~DpiScope() { if (previous) SetThreadDpiAwarenessContext(previous); }
};
struct Request {
    HWND owner = nullptr, input = nullptr;
    int x = 0, y = 0, width = 0, height = 0;
};
struct CaptureSignal {
    HANDLE event = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    CaptureSignal() { check_bool(event != nullptr); }
    ~CaptureSignal() { CloseHandle(event); }
    void notify() const { SetEvent(event); }
};
int64_t clock100ns() {
    static const int64_t frequency = [] { LARGE_INTEGER value{}; QueryPerformanceFrequency(&value); return value.QuadPart; }();
    LARGE_INTEGER value{}; QueryPerformanceCounter(&value);
    return value.QuadPart / frequency * 10'000'000 + value.QuadPart % frequency * 10'000'000 / frequency;
}
POINT clientOrigin(HWND owner) {
    POINT point{};
    check_bool(IsWindow(owner) && ClientToScreen(owner, &point));
    return point;
}
struct Exclusion { DWORD original; size_t users; };
std::mutex exclusionMutex;
std::map<HWND, Exclusion> exclusions;
void exclude(HWND window) {
    std::lock_guard lock(exclusionMutex);
    if (auto existing = exclusions.find(window); existing != exclusions.end()) { ++existing->second.users; return; }
    DWORD original = 0, verified = 0;
    check_bool(GetWindowDisplayAffinity(window, &original));
    check_bool(SetWindowDisplayAffinity(window, WDA_EXCLUDEFROMCAPTURE));
    if (!GetWindowDisplayAffinity(window, &verified) || verified != WDA_EXCLUDEFROMCAPTURE) {
        SetWindowDisplayAffinity(window, original);
        throw hresult_error(E_FAIL, L"The island window could not be excluded from desktop capture");
    }
    exclusions.emplace(window, Exclusion{ original, 1 });
}
void releaseExclusion(HWND window) noexcept {
    std::lock_guard lock(exclusionMutex);
    auto previous = exclusions.find(window);
    if (previous == exclusions.end() || --previous->second.users) return;
    if (IsWindow(window)) SetWindowDisplayAffinity(window, previous->second.original);
    exclusions.erase(previous);
}
com_ptr<IDXGIOutput6> colorOutput(HMONITOR monitor) {
    com_ptr<IDXGIFactory1> factory;
    check_hresult(CreateDXGIFactory1(__uuidof(IDXGIFactory1), factory.put_void()));
    for (UINT a = 0;; ++a) {
        com_ptr<IDXGIAdapter1> adapter;
        if (factory->EnumAdapters1(a, adapter.put()) == DXGI_ERROR_NOT_FOUND) break;
        if (!adapter) continue;
        for (UINT o = 0;; ++o) {
            com_ptr<IDXGIOutput> output;
            if (adapter->EnumOutputs(o, output.put()) == DXGI_ERROR_NOT_FOUND) break;
            if (!output) continue;
            DXGI_OUTPUT_DESC description{};
            check_hresult(output->GetDesc(&description));
            if (description.Monitor != monitor) continue;
            return output.try_as<IDXGIOutput6>();
        }
    }
    throw hresult_error(E_FAIL, L"Cannot identify the capture display");
}
void requireSdr(com_ptr<IDXGIOutput6> const& output) {
    if (!output) return;
    DXGI_OUTPUT_DESC1 color{};
    check_hresult(output->GetDesc1(&color));
    if (color.ColorSpace == DXGI_COLOR_SPACE_RGB_FULL_G2084_NONE_P2020)
        throw hresult_error(E_NOTIMPL, L"Liquid Glass currently supports SDR displays only");
}

class Capture {
    std::mutex mutex;
    std::shared_ptr<CaptureSignal> signal = std::make_shared<CaptureSignal>();
    std::shared_ptr<CaptureSignal> publishedSignal = std::make_shared<CaptureSignal>();
    Request requested;
    bool stopping = false;
    std::vector<uint8_t> published;
    std::shared_ptr<DesktopSharedTexture> publishedGpu;
    std::wstring adapterName;
    int adapterPriority = 0;
    std::atomic<bool> forceCpu = false;
    RECT publishedRect{};
    uint64_t serial = 0;
    bool ready = false;
    int64_t transportUs = 0;
    std::string failure;
    std::string gpuFailure;
    std::thread worker; // Start only after every shared state member has been constructed.

    void gpuFallback(char const* stage, HRESULT code) {
        char result[16]{};
        std::snprintf(result, sizeof(result), "0x%08lX", static_cast<unsigned long>(code));
        std::lock_guard lock(mutex);
        gpuFailure = std::string(stage) + " (HRESULT " + result + ")";
    }

    void run() noexcept {
        std::set<HWND> excluded;
        bool apartment = false;
        try {
            init_apartment(apartment_type::multi_threaded);
            apartment = true;
            DpiScope dpi;
            if (!ApiInformation::IsPropertyPresent(L"Windows.Graphics.Capture.GraphicsCaptureSession", L"IsBorderRequired") ||
                !GraphicsCaptureSession::IsSupported())
                throw hresult_error(E_NOTIMPL, L"Borderless desktop capture requires Windows 11 or newer");
            const auto permission = GraphicsCaptureAccess::RequestAccessAsync(GraphicsCaptureAccessKind::Borderless);
            while (permission.Status() == winrt::Windows::Foundation::AsyncStatus::Started) {
                WaitForSingleObject(signal->event, 50);
                std::lock_guard lock(mutex);
                if (stopping) { permission.Cancel(); throw hresult_error(E_ABORT, L"Desktop capture stopped"); }
            }
            const auto access = permission.get();
            if (access != AppCapabilityAccessStatus::Allowed)
                throw hresult_error(E_ACCESSDENIED, L"Windows denied borderless desktop capture");

            com_ptr<ID3D11Device> device;
            com_ptr<ID3D11DeviceContext> context;
            com_ptr<IDXGIAdapter1> adapter;
            if (!adapterName.empty()) {
                try { adapter = DesktopSharedTexture::adapter(adapterName, adapterPriority); }
                catch (...) { gpuFallback("DXGI adapter matching", winrt::to_hresult()); }
            } else gpuFallback("Skiko adapter was not supplied", E_NOINTERFACE);
            const auto createDevice = [&] {
                return D3D11CreateDevice(adapter.get(), adapter ? D3D_DRIVER_TYPE_UNKNOWN : D3D_DRIVER_TYPE_HARDWARE, nullptr,
                    D3D11_CREATE_DEVICE_BGRA_SUPPORT, nullptr, 0, D3D11_SDK_VERSION,
                    device.put(), nullptr, context.put());
            };
            auto creation = createDevice();
            if (FAILED(creation) && adapter) {
                gpuFallback("Capture D3D11CreateDevice", creation);
                adapter = nullptr; device = nullptr; context = nullptr;
                creation = createDevice(); // Shared transport must not disable the existing CPU path.
            }
            check_hresult(creation);
            auto dxgi = device.as<IDXGIDevice>();
            com_ptr<IInspectable> inspectable;
            check_hresult(CreateDirect3D11DeviceFromDXGIDevice(dxgi.get(), inspectable.put()));
            auto runtimeDevice = inspectable.as<IDirect3DDevice>();
            HMONITOR monitor = nullptr;
            com_ptr<IDXGIOutput6> displayColor;
            auto nextColorCheck = std::chrono::steady_clock::now();
            MONITORINFO monitorInfo{ sizeof(MONITORINFO) };
            GraphicsCaptureItem item{ nullptr };
            Direct3D11CaptureFramePool pool{ nullptr };
            GraphicsCaptureSession session{ nullptr };
            event_token itemClosed{}, frameArrived{};
            auto closedSignal = std::make_shared<std::atomic<bool>>(false);
            com_ptr<ID3D11Texture2D> desktop;
            Direct3D11CaptureFrame heldFrame{ nullptr };
            DesktopReadback readback(device.get(), context.get());
            if (adapter) {
                try {
                    // D3D12CreateDevice returns this process's existing device for the same LUID.
                    com_ptr<ID3D12Device> consumer;
                    check_hresult(D3D12CreateDevice(adapter.get(), D3D_FEATURE_LEVEL_11_0,
                        __uuidof(ID3D12Device), consumer.put_void()));
                    if (!readback.shareWith(consumer.get()))
                        gpuFallback("D3D11 fence initialization", readback.sharingFailure());
                } catch (hresult_error const& error) {
                    gpuFallback("D3D12CreateDevice for sharing", error.code()); readback.useCpu();
                } catch (...) { gpuFallback("D3D12 sharing initialization", winrt::to_hresult()); readback.useCpu(); }
            }
            DesktopViewport viewport;
            DesktopReadback::Frame completed;
            int contentWidth = 0, contentHeight = 0;
            RECT previousCrop{};
            uint64_t desktopVersion = 0, submittedVersion = 0;
            int64_t desktopTime = 0;
            const auto closeSession = [&] {
                { std::lock_guard lock(mutex); ready = false; publishedGpu.reset(); }
                try { if (pool) pool.FrameArrived(frameArrived); } catch (...) {}
                try { if (item) item.Closed(itemClosed); } catch (...) {}
                try { if (heldFrame) heldFrame.Close(); } catch (...) {}
                heldFrame = nullptr;
                try { if (session) session.Close(); } catch (...) {}
                try { if (pool) pool.Close(); } catch (...) {}
                session = nullptr; pool = nullptr; item = nullptr;
                desktop = nullptr; readback.reset(); viewport.reset();
                contentWidth = 0; contentHeight = 0;
            };
            try {
                for (;;) {
                    Request request;
                    // Frames and view changes wake immediately. A pending GPU copy is polled
                    // without blocking; otherwise the worker can remain asleep over a static desktop.
                    if (auto gpuCompleted = readback.completion()) {
                        const HANDLE events[]{ signal->event, gpuCompleted };
                        WaitForMultipleObjects(2, events, FALSE, 100);
                    } else WaitForSingleObject(signal->event, !pool ? 0 : readback.pending() ? 1 : 100);
                    {
                        std::lock_guard lock(mutex);
                        if (stopping) break;
                        request = requested;
                    }
                    if (!request.owner || request.width <= 0 || request.height <= 0) continue;
                    if (forceCpu.load() && readback.shared()) {
                        readback.useCpu(); submittedVersion = 0; previousCrop = {};
                        std::lock_guard lock(mutex); ready = false; publishedGpu.reset();
                    }
                    if (request.width > 16384 || request.height > 16384 ||
                        static_cast<uint64_t>(request.width) * request.height * 4 > 64'000'000)
                        throw hresult_error(E_INVALIDARG, L"Desktop capture crop is too large");
                    bool exclusionsChanged = false;
                    // The input peer can be created after capture starts. Exclude both top-level HWNDs.
                    for (auto iterator = excluded.begin(); iterator != excluded.end();) {
                        if (*iterator != request.owner && *iterator != request.input) {
                            releaseExclusion(*iterator);
                            iterator = excluded.erase(iterator);
                        } else ++iterator;
                    }
                    for (HWND window : { request.owner, request.input }) {
                        if (!window || excluded.contains(window)) continue;
                        exclude(window);
                        excluded.emplace(window);
                        exclusionsChanged = true;
                    }
                    const auto origin = clientOrigin(request.owner);
                    RECT desired{ origin.x + request.x, origin.y + request.y,
                        origin.x + request.x + request.width, origin.y + request.y + request.height };
                    const auto nextMonitor = MonitorFromRect(&desired, MONITOR_DEFAULTTONEAREST);
                    MONITORINFO nextInfo{ sizeof(MONITORINFO) };
                    check_bool(GetMonitorInfoW(nextMonitor, &nextInfo));
                    if (nextMonitor != monitor || exclusionsChanged || !EqualRect(&nextInfo.rcMonitor, &monitorInfo.rcMonitor)) {
                        closeSession();
                        monitor = nextMonitor;
                        monitorInfo = nextInfo;
                        displayColor = colorOutput(monitor);
                        requireSdr(displayColor);
                        nextColorCheck = std::chrono::steady_clock::now() + std::chrono::seconds(1);
                        const auto interop = get_activation_factory<GraphicsCaptureItem, IGraphicsCaptureItemInterop>();
                        check_hresult(interop->CreateForMonitor(monitor, guid_of<GraphicsCaptureItem>(), put_abi(item)));
                        closedSignal = std::make_shared<std::atomic<bool>>(false);
                        itemClosed = item.Closed([closed = closedSignal, wake = signal](auto const&, auto const&) {
                            closed->store(true); wake->notify();
                        });
                        pool = Direct3D11CaptureFramePool::CreateFreeThreaded(runtimeDevice,
                            DirectXPixelFormat::B8G8R8A8UIntNormalized, 3, item.Size());
                        frameArrived = pool.FrameArrived([wake = signal](auto const&, auto const&) { wake->notify(); });
                        session = pool.CreateCaptureSession(item);
                        session.IsCursorCaptureEnabled(false);
                        session.IsBorderRequired(false);
                        if (auto limited = session.try_as<IGraphicsCaptureSession5>())
                            limited.MinUpdateInterval(std::chrono::milliseconds(0));
                        session.StartCapture();
                    }
                    if (closedSignal->load()) throw hresult_error(E_FAIL, L"The captured display is no longer available");
                    if (std::chrono::steady_clock::now() >= nextColorCheck) {
                        requireSdr(displayColor);
                        nextColorCheck = std::chrono::steady_clock::now() + std::chrono::seconds(1);
                    }
                    bool resized = false;
                    winrt::Windows::Graphics::SizeInt32 newSize{};
                    Direct3D11CaptureFrame latest{ nullptr };
                    while (auto frame = pool.TryGetNextFrame()) {
                        if (latest) latest.Close();
                        latest = std::move(frame);
                    }
                    if (latest) {
                        const auto size = latest.ContentSize();
                        auto accessSurface = latest.Surface().as<::Windows::Graphics::DirectX::Direct3D11::IDirect3DDxgiInterfaceAccess>();
                        com_ptr<ID3D11Texture2D> texture;
                        check_hresult(accessSurface->GetInterface(__uuidof(ID3D11Texture2D), texture.put_void()));
                        D3D11_TEXTURE2D_DESC description{};
                        texture->GetDesc(&description);
                        if (size.Width != static_cast<int>(description.Width) || size.Height != static_cast<int>(description.Height)) {
                            newSize = size; resized = true; latest.Close();
                        } else {
                            if (heldFrame) heldFrame.Close();
                            heldFrame = std::move(latest); desktop = std::move(texture);
                            contentWidth = size.Width; contentHeight = size.Height;
                            desktopTime = heldFrame.SystemRelativeTime().count();
                            ++desktopVersion;
                        }
                    }
                    if (resized) {
                        { std::lock_guard lock(mutex); ready = false; }
                        if (heldFrame) heldFrame.Close();
                        heldFrame = nullptr; readback.reset(); viewport.reset();
                        if (newSize.Width > 0 && newSize.Height > 0)
                            pool.Recreate(runtimeDevice, DirectXPixelFormat::B8G8R8A8UIntNormalized, 3, newSize);
                        desktop = nullptr;
                        continue;
                    }
                    if (!desktop) continue;
                    const RECT available{ monitorInfo.rcMonitor.left, monitorInfo.rcMonitor.top,
                        monitorInfo.rcMonitor.left + contentWidth, monitorInfo.rcMonitor.top + contentHeight };
                    const RECT crop = viewport.select(desired, available);
                    const int width = crop.right - crop.left, height = crop.bottom - crop.top;
                    if (width <= 0 || height <= 0) continue;
                    if (readback.takeLatest(context.get(), completed)) {
                        // The worker is the only writer; comparison can run beside the UI's
                        // read-only copy. Hold the publication lock only for the atomic swap.
                        if (completed.shared || !ready || !EqualRect(&publishedRect, &completed.crop) || published != completed.pixels) {
                            std::lock_guard lock(mutex);
                            published.swap(completed.pixels); publishedGpu = std::move(completed.shared);
                            publishedRect = completed.crop; ++serial;
                            transportUs = std::max<int64_t>(0, (clock100ns() - completed.sourceTime) / 10);
                            ready = true;
                            publishedSignal->notify();
                        }
                    }
                    if (desktopVersion != submittedVersion || !EqualRect(&crop, &previousCrop)) {
                        try { if (readback.submit(device.get(), context.get(), desktop.get(), crop,
                            POINT{ monitorInfo.rcMonitor.left, monitorInfo.rcMonitor.top },
                            desktopVersion != submittedVersion ? desktopTime : clock100ns())) {
                            submittedVersion = desktopVersion; previousCrop = crop;
                        } } catch (hresult_error const& error) {
                            if (!readback.shared()) throw;
                            const auto operation = "Shared texture: " + to_string(error.message());
                            gpuFallback(operation.c_str(), error.code());
                            forceCpu.store(true); signal->notify();
                        } catch (...) {
                            if (!readback.shared()) throw;
                            gpuFallback("Shared texture submission", winrt::to_hresult());
                            forceCpu.store(true); signal->notify();
                        }
                    }
                }
            } catch (...) { closeSession(); throw; }
            closeSession();
        } catch (hresult_error const& error) {
            std::lock_guard lock(mutex);
            failure = to_string(error.message()) + " (HRESULT " + std::to_string(error.code().value) + ")";
        } catch (std::exception const& error) {
            std::lock_guard lock(mutex); failure = error.what();
        } catch (...) {
            std::lock_guard lock(mutex); failure = "Unexpected desktop capture failure";
        }
        publishedSignal->notify();
        for (HWND window : excluded) releaseExclusion(window);
        if (apartment) uninit_apartment();
    }
public:
    explicit Capture(std::wstring adapter = {}, int priority = 0)
        : adapterName(std::move(adapter)), adapterPriority(priority), worker([this] { run(); }) {}
    ~Capture() { stop(); worker.join(); }
    void stop() {
        { std::lock_guard lock(mutex); stopping = true; }
        signal->notify(); publishedSignal->notify();
    }
    int wait(DWORD timeout) {
        const auto result = WaitForSingleObject(publishedSignal->event, timeout);
        std::lock_guard lock(mutex);
        if (stopping || !failure.empty() || result == WAIT_FAILED) return -1;
        return result == WAIT_OBJECT_0 ? 1 : 0;
    }
    void request(HWND owner, HWND input, int x, int y, int width, int height) {
        std::lock_guard lock(mutex);
        auto& previous = requested;
        if (previous.owner == owner && previous.input == input && previous.x == x && previous.y == y &&
            previous.width == width && previous.height == height) { signal->notify(); return; }
        requested = { owner, input, x, y, width, height };
        signal->notify();
    }
    void useCpu() { forceCpu.store(true); signal->notify(); }
    void describe(int64_t* metadata) {
        const auto origin = clientOrigin(requested.owner);
        metadata[0] = serial; metadata[1] = publishedRect.left; metadata[2] = publishedRect.top;
        metadata[3] = publishedRect.right - publishedRect.left; metadata[4] = publishedRect.bottom - publishedRect.top;
        metadata[5] = origin.x; metadata[6] = origin.y; metadata[7] = transportUs;
        metadata[8] = 0; metadata[9] = publishedGpu ? 1 : 0;
    }
    void* acquireGpu(int64_t* metadata) {
        DpiScope dpi;
        std::lock_guard lock(mutex);
        if (!ready || !publishedGpu) return nullptr;
        describe(metadata);
        metadata[8] = reinterpret_cast<int64_t>(publishedGpu->resource.get());
        return new std::shared_ptr<DesktopSharedTexture>(publishedGpu);
    }
    int read(uint64_t previousSerial, uint8_t* pixels, int capacity, int64_t* metadata) {
        DpiScope dpi;
        std::lock_guard lock(mutex);
        if (!failure.empty()) return -1;
        if (!ready) return 3;
        describe(metadata);
        if (previousSerial == serial) return 0;
        if (publishedGpu) return 4;
        if (!pixels || capacity < static_cast<int>(published.size())) return 2;
        std::memcpy(pixels, published.data(), published.size());
        return 1;
    }
    void error(char* buffer, int capacity) {
        std::lock_guard lock(mutex);
        if (capacity <= 0) return;
        const size_t count = std::min(failure.size(), static_cast<size_t>(capacity - 1));
        std::memcpy(buffer, failure.data(), count); buffer[count] = 0;
    }
    void gpuError(char* buffer, int capacity) {
        std::lock_guard lock(mutex);
        if (capacity <= 0) return;
        const size_t count = std::min(gpuFailure.size(), static_cast<size_t>(capacity - 1));
        std::memcpy(buffer, gpuFailure.data(), count); buffer[count] = 0;
    }
};
}

extern "C" {
__declspec(dllexport) void* GlassCreate() noexcept { try { return new Capture(); } catch (...) { return nullptr; } }
__declspec(dllexport) void* GlassCreateGpu(wchar_t const* adapter) noexcept {
    try { return new Capture(adapter ? adapter : L""); } catch (...) { return nullptr; }
}
__declspec(dllexport) void* GlassCreateGpuWithPreference(wchar_t const* adapter, int priority) noexcept {
    try { return new Capture(adapter ? adapter : L"", priority); } catch (...) { return nullptr; }
}
__declspec(dllexport) void GlassUseCpu(void* capture) noexcept { static_cast<Capture*>(capture)->useCpu(); }
__declspec(dllexport) void* GlassAcquireGpu(void* capture, int64_t* metadata) noexcept {
    try { return static_cast<Capture*>(capture)->acquireGpu(metadata); } catch (...) { return nullptr; }
}
__declspec(dllexport) void GlassReleaseGpu(void* lease) noexcept {
    delete static_cast<std::shared_ptr<DesktopSharedTexture>*>(lease);
}
__declspec(dllexport) void GlassRequest(void* capture, HWND owner, HWND input, int x, int y, int width, int height) noexcept {
    static_cast<Capture*>(capture)->request(owner, input, x, y, width, height);
}
__declspec(dllexport) int GlassRead(void* capture, uint64_t previous, uint8_t* pixels, int capacity, int64_t* metadata) noexcept {
    try { return static_cast<Capture*>(capture)->read(previous, pixels, capacity, metadata); } catch (...) { return -1; }
}
__declspec(dllexport) int GlassWait(void* capture, int timeout) noexcept {
    return static_cast<Capture*>(capture)->wait(static_cast<DWORD>(std::clamp(timeout, 0, 1000)));
}
__declspec(dllexport) void GlassError(void* capture, char* buffer, int capacity) noexcept { static_cast<Capture*>(capture)->error(buffer, capacity); }
__declspec(dllexport) void GlassGpuError(void* capture, char* buffer, int capacity) noexcept { static_cast<Capture*>(capture)->gpuError(buffer, capacity); }
__declspec(dllexport) void GlassStop(void* capture) noexcept { static_cast<Capture*>(capture)->stop(); }
__declspec(dllexport) void GlassDestroy(void* capture) noexcept { delete static_cast<Capture*>(capture); }
}
