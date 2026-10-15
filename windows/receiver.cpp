#ifndef UNICODE
#define UNICODE
#endif
#define _UNICODE
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <windowsx.h>
#include <gdiplus.h>
#include <shlwapi.h>
#include <vector>
#include <algorithm>
#include <thread>
#include <mutex>
#include <atomic>
#include <string>
#include <memory>
#include <cstring>
#include <cstdio>
#include <cstddef>
#include <condition_variable>
#include <chrono>
#include <cstdint>
#include <array>
#include <map>
#include <cmath>
using namespace Gdiplus;
static HWND windowHandle;
static constexpr UINT WM_SOURCE_MODE = WM_APP + 2;
static std::atomic<int> streamSource{0}; // 0 unknown; 1 UVC; 2 phone screen

// JPEG decode is isolated from TCP. ACK means the complete bounded packet was
// accepted, NOT that it was decoded or painted; latest-frame-wins is intentional.
struct DecodedFrame {
    HBITMAP bitmap = nullptr;
    unsigned width = 0;
    unsigned height = 0;
    ~DecodedFrame() { if (bitmap) DeleteObject(bitmap); }
};
static std::mutex lockFrame;
static std::shared_ptr<DecodedFrame> picture;
static std::atomic<bool> linkConnected{false};
static std::atomic<unsigned long> rejectedFrames{0};
static std::atomic<unsigned long> decodedFrames{0};
static std::atomic<unsigned long> droppedFrames{0};
static std::atomic<unsigned long> lastDecodeMs{0};
static std::atomic<unsigned long> lastPaintMs{0};
static std::atomic<unsigned long> lastReceiveMs{0};
static std::atomic<unsigned long> maxDecodeMs{0};
static std::atomic<bool> alive{true};
static std::mutex lockPending;
static std::condition_variable pendingCV;
static std::vector<unsigned char> pendingJPEG;
static bool hasPending = false;
static std::uint64_t pendingSession = 0;
static std::atomic<std::uint64_t> sessionGeneration{0};
static std::thread decodeWorker;
static std::atomic<bool> refreshPosted{false};
static void queueRefresh() {
    if (!refreshPosted.exchange(true)) PostMessage(windowHandle, WM_APP, 0, 0);
}
static SOCKET listener = INVALID_SOCKET;
static SOCKET peer = INVALID_SOCKET;
static SOCKET discoverySocket = INVALID_SOCKET;
static std::thread discoveryWorker;
static std::mutex lockSocket;
static std::thread worker;
static std::atomic<bool> remoteControlEnabled{true};
static std::mutex controlMutex;
static std::vector<std::array<unsigned char,17>> controlQueue;
static POINT dragStart{};
static POINT dragLast{};
static ULONGLONG dragStarted = 0;
static bool mouseDragging = false;
static ULONGLONG lastTouchMs = 0;
static std::map<UINT32,POINT> touchStart, touchEnd;
// Aggregate rapid wheel detents into one two-finger gesture per short window.
// Android runs gestures serially; flooding it with independent strokes cancels
// the previous stroke on Android AccessibilityService.
static constexpr UINT_PTR PINCH_TIMER_ID = 16;
static constexpr UINT PINCH_BATCH_MS = 160;
static int pinchPendingDetents = 0;
static int pinchWheelRemainder = 0;
static bool pinchTimerArmed = false;
static POINT pinchAnchor{};
static void clearPendingPinch(HWND hwnd) {
    if (pinchTimerArmed) KillTimer(hwnd, PINCH_TIMER_ID);
    pinchTimerArmed = false;
    pinchPendingDetents = 0;
    pinchWheelRemainder = 0;
}

static bool normalized(HWND hwnd, POINT pt, uint16_t &nx, uint16_t &ny) {
    RECT client{}; GetClientRect(hwnd,&client);
    std::shared_ptr<DecodedFrame> frame;
    { std::lock_guard<std::mutex> guard(lockFrame); frame = picture; }
    if (!frame || frame->width == 0 || frame->height == 0) return false;
    double scale = (std::min)(double(client.right)/frame->width,double(client.bottom)/frame->height);
    const int w=int(frame->width*scale),h=int(frame->height*scale);
    const int left=(client.right-w)/2, top=(client.bottom-h)/2;
    if (w <= 0 || h <= 0 || pt.x < left || pt.y < top || pt.x >= left+w || pt.y >= top+h) return false;
    nx=uint16_t((std::min)(65535.0,(std::max)(0.0,(pt.x-left)*65535.0/w)));
    ny=uint16_t((std::min)(65535.0,(std::max)(0.0,(pt.y-top)*65535.0/h)));
    return true;
}
static void enqueueControl(HWND hwnd, int op, POINT from, POINT to, POINT secondFrom = {}, POINT secondTo = {}) {
    if (!remoteControlEnabled.load() || !linkConnected.load()) return;
    uint16_t x1,y1,x2,y2;
    if (!normalized(hwnd,from,x1,y1) || !normalized(hwnd,to,x2,y2)) return;
    std::array<unsigned char,17> data{};
    data[0]=static_cast<unsigned char>(op);
    auto put=[&](int i,uint16_t v){data[i]=static_cast<unsigned char>(v>>8);data[i+1]=static_cast<unsigned char>(v & 255);};
    put(1,x1);put(3,y1);put(5,x2);put(7,y2);
    if (op == 4) {
        uint16_t xa,ya,xb,yb;
        if (!normalized(hwnd,secondFrom,xa,ya) || !normalized(hwnd,secondTo,xb,yb)) return;
        put(9,xa);put(11,ya);put(13,xb);put(15,yb);
    }
    std::lock_guard<std::mutex> lock(controlMutex);
    if (controlQueue.size() >= 64) controlQueue.erase(controlQueue.begin());
    controlQueue.push_back(data);
}
// Remote UTF-8 keyboard events use the same bounded 17-byte reverse ACK as
// MMC6 gestures. Merge consecutive committed characters up to 15 bytes; each
// received Unicode scalar is encoded whole so packets cannot split a codepoint.
static void enqueueKeyboardText(const unsigned char* utf8, size_t length) {
    if (!remoteControlEnabled.load() || !linkConnected.load() ||
        streamSource.load() != 2 || length == 0 || length > 4) return;
    std::lock_guard<std::mutex> guard(controlMutex);
    if (!controlQueue.empty() && controlQueue.back()[0] == 8 &&
        controlQueue.back()[1] + length <= 15) {
        auto& tail = controlQueue.back();
        std::memcpy(tail.data() + 2 + tail[1], utf8, length);
        tail[1] = static_cast<unsigned char>(tail[1] + length);
        return;
    }
    if (controlQueue.size() >= 64) return; // never replay unbounded text
    std::array<unsigned char,17> pkt{};
    pkt[0] = 8;
    pkt[1] = static_cast<unsigned char>(length);
    std::memcpy(pkt.data()+2, utf8, length);
    controlQueue.push_back(pkt);
}
static void enqueueKeyboardCodepoint(std::uint32_t cp) {
    if (cp < 0x20 || cp == 0x7f || cp > 0x10ffff ||
        (cp >= 0xd800 && cp <= 0xdfff)) return;
    unsigned char bytes[4]{};
    size_t n = 0;
    if (cp < 0x80) bytes[n++] = static_cast<unsigned char>(cp);
    else if (cp < 0x800) {
        bytes[n++] = static_cast<unsigned char>(0xc0 | (cp >> 6));
        bytes[n++] = static_cast<unsigned char>(0x80 | (cp & 0x3f));
    } else if (cp < 0x10000) {
        bytes[n++] = static_cast<unsigned char>(0xe0 | (cp >> 12));
        bytes[n++] = static_cast<unsigned char>(0x80 | ((cp >> 6) & 0x3f));
        bytes[n++] = static_cast<unsigned char>(0x80 | (cp & 0x3f));
    } else {
        bytes[n++] = static_cast<unsigned char>(0xf0 | (cp >> 18));
        bytes[n++] = static_cast<unsigned char>(0x80 | ((cp >> 12) & 0x3f));
        bytes[n++] = static_cast<unsigned char>(0x80 | ((cp >> 6) & 0x3f));
        bytes[n++] = static_cast<unsigned char>(0x80 | (cp & 0x3f));
    }
    enqueueKeyboardText(bytes, n);
}
static void enqueueKeyboardKey(int id) {
    if (!remoteControlEnabled.load() || !linkConnected.load() ||
        streamSource.load() != 2) return;
    std::lock_guard<std::mutex> guard(controlMutex);
    if (controlQueue.size() >= 64) return;
    std::array<unsigned char,17> pkt{};
    pkt[0] = 9;
    pkt[1] = static_cast<unsigned char>(id);
    controlQueue.push_back(pkt);
}
static wchar_t pendingHighSurrogate = 0;

// One pinch gesture covers all wheel detents accumulated during 160ms.
// Derive the stroke from the displayed image rectangle, not fixed pixels:
// portrait phones, letterboxing, and window resizing all stay in bounds.
static void flushPinchWheel(HWND hwnd) {
    const int detents = pinchPendingDetents;
    pinchPendingDetents = 0;
    if (!detents || !remoteControlEnabled.load() || !linkConnected.load() ||
        streamSource.load() == 1) return;
    RECT client{}; GetClientRect(hwnd, &client);
    std::shared_ptr<DecodedFrame> frame;
    { std::lock_guard<std::mutex> guard(lockFrame); frame = picture; }
    if (!frame || !frame->width || !frame->height ||
        client.right <= 0 || client.bottom <= 0) return;
    const double fit = (std::min)(double(client.right) / frame->width,
                                   double(client.bottom) / frame->height);
    const int width = int(frame->width * fit), height = int(frame->height * fit);
    const int span = (std::min)(width, height);
    if (span < 40) return;
    const int left = (client.right - width) / 2, top = (client.bottom - height) / 2;
    const int startRadius = (std::max)(4, int(span * 0.19));
    const int strength = (std::min)(6, abs(detents));
    const double scale = std::pow(1.52, strength);
    const int endRadius = (std::max)(int(span * 0.045), (std::min)(
        int(span * 0.42), int(startRadius * (detents < 0 ? (1.0 / scale) : scale))));
    const int safeRadius = (std::max)(startRadius, endRadius) + 2;
    const int midX = (std::clamp)(static_cast<int>(pinchAnchor.x), left + safeRadius, left + width - safeRadius - 1);
    const int midY = (std::clamp)(static_cast<int>(pinchAnchor.y), top + 1, top + height - 2);
    POINT a{midX-startRadius, midY}, b{midX-endRadius, midY};
    POINT c{midX+startRadius, midY}, d{midX+endRadius, midY};
    // UP (positive detents) moves fingers OUT and zooms IN; DOWN moves IN and zooms OUT.
    enqueueControl(hwnd, 4, a, b, c, d);
}

static bool sendControlAck(SOCKET s, bool enhanced) {
    char ack[18] = {6};
    if (enhanced) {
        std::lock_guard<std::mutex> guard(controlMutex);
        if (remoteControlEnabled.load() && !controlQueue.empty()) {
            const auto packet=controlQueue.front();
            controlQueue.erase(controlQueue.begin());
            std::memcpy(ack+1,packet.data(),packet.size());
        }
    }
    int sent=0, count=enhanced?18:1;
    while (sent<count) {int n=send(s,ack+sent,count-sent,0);if(n<=0)return false;sent+=n;}
    return true;
}

static bool readExact(SOCKET s, char* dst, int size) {
    while (size > 0 && alive) { int n = recv(s, dst, size, 0); if (n <= 0) return false; dst += n; size -= n; }
    return size == 0;
}
#include "audio_receiver.h"
// Preserve the last valid image through transient disconnections.
// It remains visibly marked as stale until a new frame arrives.
static void updateLink(bool connected) {
    linkConnected.store(connected);
    queueRefresh();
}
// V014: replies include the exact random per-search nonce, so a DHCP server,
// stale UDP packet or another kind of responder cannot be mistaken for our EXE.
static void discoveryLoop() {
    SOCKET s = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (s == INVALID_SOCKET) return;
    discoverySocket = s;
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = INADDR_ANY;
    address.sin_port = htons(57008);
    if (bind(s, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0) {
        closesocket(s);
        discoverySocket = INVALID_SOCKET;
        return;
    }
    DWORD timeout = 250;
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<char*>(&timeout), sizeof(timeout));
    const std::string prefix = "MYMONITOR_DISCOVER_V2|";
    const char legacyRequest[] = "MYMONITOR_DISCOVER_V1";
    const char legacyReply[] = "MYCAST_RECEIVER_V1|57007";
    char hostname[64] = "Windows";
    DWORD hostLen = sizeof(hostname);
    if (!GetComputerNameA(hostname, &hostLen)) std::strcpy(hostname, "Windows");
    // Keep the discovery response small and ASCII-only.
    for (char &ch : hostname) {
        if (!ch) break;
        if ((unsigned char)ch < 33 || (unsigned char)ch > 126 || ch == '|') ch = '_';
    }
    while (alive) {
        char buffer[160] = {};
        sockaddr_in from{};
        int fromSize = sizeof(from);
        int size = recvfrom(s, buffer, sizeof(buffer), 0, reinterpret_cast<sockaddr*>(&from), &fromSize);
        if (size <= 0) continue;
        if (size == sizeof(legacyRequest)-1 &&
            std::memcmp(buffer, legacyRequest, sizeof(legacyRequest)-1) == 0) {
            // Backward compatibility: existing 012/013 senders can still find 014.
            sendto(s, legacyReply, sizeof(legacyReply)-1, 0,
                reinterpret_cast<sockaddr*>(&from), fromSize);
            continue;
        }
        if (size == (int)prefix.size()+32 && std::memcmp(buffer, prefix.data(), prefix.size()) == 0) {
            bool hex = true;
            for (int i = 0; i < 32; ++i) {
                const char ch = buffer[prefix.size()+i];
                if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f'))) {
                    hex = false; break;
                }
            }
            if (!hex) continue;
            std::string reply = "MYCAST_RECEIVER_V2|" +
                std::string(buffer + prefix.size(), 32) + "|57007|" + hostname;
            sendto(s, reply.data(), static_cast<int>(reply.size()), 0,
                reinterpret_cast<sockaddr*>(&from), fromSize);
        }
    }
    closesocket(s);
    discoverySocket = INVALID_SOCKET;
}

// Convert to a top-down 32-bit DIB once on the decoder thread. Windows WM_PAINT
// then uses native BitBlt / StretchBlt with no GDI+ DrawImage and no temporary
// 1920x1080 compatible bitmap per frame. The published HBITMAP is reference
// counted so UI painting never holds the frame mutex during GDI calls.
static std::shared_ptr<DecodedFrame> decodeJPEG(const std::vector<unsigned char>& jpeg) {
    if (jpeg.empty()) return {};
    IStream* stream = SHCreateMemStream(jpeg.data(), static_cast<UINT>(jpeg.size()));
    if (!stream) return {};
    std::shared_ptr<DecodedFrame> result;
    {
        Bitmap decoded(stream);
        const UINT w = decoded.GetWidth(), h = decoded.GetHeight();
        if (decoded.GetLastStatus() == Ok && w && h && w <= 4096 && h <= 4096) {
            Rect rect(0, 0, static_cast<INT>(w), static_cast<INT>(h));
            BitmapData pixels{};
            if (decoded.LockBits(&rect, ImageLockModeRead, PixelFormat32bppRGB, &pixels) == Ok) {
                BITMAPINFO info{};
                info.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
                info.bmiHeader.biWidth = static_cast<LONG>(w);
                info.bmiHeader.biHeight = -static_cast<LONG>(h); // top-down DIB
                info.bmiHeader.biPlanes = 1;
                info.bmiHeader.biBitCount = 32;
                info.bmiHeader.biCompression = BI_RGB;
                void* bits = nullptr;
                HBITMAP dib = CreateDIBSection(nullptr, &info, DIB_RGB_COLORS, &bits, nullptr, 0);
                if (dib && bits) {
                    auto* dst = static_cast<unsigned char*>(bits);
                    const auto* src = static_cast<const unsigned char*>(pixels.Scan0);
                    const size_t stride = size_t(w) * 4;
                    for (UINT y = 0; y < h; ++y) {
                        std::memcpy(dst + size_t(y)*stride,
                            src + static_cast<std::ptrdiff_t>(y)*pixels.Stride, stride);
                    }
                    result = std::make_shared<DecodedFrame>();
                    result->bitmap = dib;
                    result->width = w;
                    result->height = h;
                } else if (dib) DeleteObject(dib);
                decoded.UnlockBits(&pixels);
            }
        }
    }
    stream->Release();
    return result;
}

static void decodeLoop() {
    while (true) {
        std::vector<unsigned char> bytes;
        std::uint64_t generation = 0;
        {
            std::unique_lock<std::mutex> guard(lockPending);
            pendingCV.wait(guard, [] { return hasPending || !alive.load(); });
            if (!alive) break;
            bytes = std::move(pendingJPEG);
            generation = pendingSession;
            hasPending = false;
        }
        const ULONGLONG begun = GetTickCount64();
        std::shared_ptr<DecodedFrame> decoded = decodeJPEG(bytes);
        const unsigned long duration = static_cast<unsigned long>(GetTickCount64() - begun);
        lastDecodeMs.store(duration);
        auto maxSeen = maxDecodeMs.load();
        while (duration > maxSeen && !maxDecodeMs.compare_exchange_weak(maxSeen, duration)) {}
        if (generation != sessionGeneration.load()) continue;
        if (decoded) {
            decodedFrames.fetch_add(1);
            {
                std::lock_guard<std::mutex> guard(lockFrame);
                picture = std::move(decoded);
            }
            linkConnected.store(true);
        } else {
            rejectedFrames.fetch_add(1);
        }
        queueRefresh();
    }
}

static void receiveLoop() {
    while (alive) {
        SOCKET s = accept(listener, nullptr, nullptr);
        if (s == INVALID_SOCKET) break;
        const std::uint64_t generation = sessionGeneration.fetch_add(1) + 1;
        BOOL noDelay = TRUE;
        setsockopt(s, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<char*>(&noDelay), sizeof(noDelay));
        DWORD timeout = 5000;
        setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<char*>(&timeout), sizeof(timeout));
        setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, reinterpret_cast<char*>(&timeout), sizeof(timeout));
        char magic[4];
        const bool readOk = readExact(s, magic, 4);
        if (readOk && std::memcmp(magic, "MCP1", 4) == 0) {
            const char proof[] = "MCR1";
            send(s, proof, 4, 0);
            closesocket(s);
            sessionGeneration.fetch_add(1);
            continue;
        }
        const bool sourceAware = readOk && std::memcmp(magic, "MMC6", 4) == 0;
        const bool enhanced = sourceAware ||
            (readOk && std::memcmp(magic, "MMC5", 4) == 0);
        if (!readOk || (!enhanced && std::memcmp(magic, "MMC9", 4) != 0)) {
            closesocket(s);
            sessionGeneration.fetch_add(1);
            continue;
        }
        {
            std::lock_guard<std::mutex> guard(lockSocket);
            if (!alive) { closesocket(s); break; }
            peer = s;
        }
        while (alive) {
            uint32_t networkLength = 0;
            if (!readExact(s, reinterpret_cast<char*>(&networkLength), 4)) break;
            const uint32_t n = ntohl(networkLength);
            if (sourceAware && n == 0xFFFFFFFFu) {
                // Explicit source metadata, never infer UVC from JPEG dimensions.
                unsigned char mode = 0;
                if (!readExact(s, reinterpret_cast<char*>(&mode), 1) || mode > 2) break;
                const int oldSource = streamSource.exchange(mode);
                if (mode == 1 && oldSource != 1) {
                    PostMessageW(windowHandle, WM_SOURCE_MODE, 1, static_cast<LPARAM>(generation));
                }
                if (!sendControlAck(s, enhanced)) break;
                continue;
            }
            if (n > 8 * 1024 * 1024) break;
            if (n == 0) {
                // v014 and later keepalive. No fake image is shown.
                if (!sendControlAck(s, enhanced)) break;
                continue;
            }
            std::vector<unsigned char> bytes(n);
            const ULONGLONG began = GetTickCount64();
            if (!readExact(s, reinterpret_cast<char*>(bytes.data()), static_cast<int>(n))) break;
            lastReceiveMs.store(static_cast<unsigned long>(GetTickCount64() - began));
            {
                std::lock_guard<std::mutex> guard(lockPending);
                if (hasPending) droppedFrames.fetch_add(1);
                pendingJPEG = std::move(bytes);
                pendingSession = generation;
                hasPending = true;
            }
            pendingCV.notify_one();
            // Critical: ACK receipt immediately, do NOT make Android wait
            // for JPEG decode, DIB allocation, UI thread or OBS redraw.
            if (!sendControlAck(s, enhanced)) break;
        }
        // Keep the last displayed frame; reject decode from a closed session.
        sessionGeneration.fetch_add(1);
        {
            std::lock_guard<std::mutex> guard(lockSocket);
            closesocket(s);
            peer = INVALID_SOCKET;
        }
        remoteControlEnabled.store(true);
        { std::lock_guard<std::mutex> guard(controlMutex); controlQueue.clear(); }
        updateLink(false);
    }
}

// Borderless monitor-sized fullscreen, with exact restoration of the prior window.
static bool isFullscreen = false;
static WINDOWPLACEMENT windowedPlacement{};
static LONG_PTR windowedStyle = 0;
static LONG_PTR windowedExStyle = 0;
static bool haveWindowedPlacement = false;

static MONITORINFOEXW currentMonitorInfo(HWND hwnd) {
    MONITORINFOEXW info{};
    info.cbSize = sizeof(info);
    GetMonitorInfoW(MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST),
        reinterpret_cast<MONITORINFO*>(&info));
    return info;
}

static bool monitorIsPhysicalFhd(const MONITORINFOEXW& info) {
    DEVMODEW mode{};
    mode.dmSize = sizeof(mode);
    if (EnumDisplaySettingsW(info.szDevice, ENUM_CURRENT_SETTINGS, &mode))
        return mode.dmPelsWidth == 1920 && mode.dmPelsHeight == 1080;
    return info.rcMonitor.right - info.rcMonitor.left == 1920 &&
           info.rcMonitor.bottom - info.rcMonitor.top == 1080;
}

static void enterFullscreen(HWND hwnd) {
    if (isFullscreen) return;
    const auto mi = currentMonitorInfo(hwnd);
    windowedStyle = GetWindowLongPtrW(hwnd, GWL_STYLE);
    windowedExStyle = GetWindowLongPtrW(hwnd, GWL_EXSTYLE);
    windowedPlacement = WINDOWPLACEMENT{};
    windowedPlacement.length = sizeof(WINDOWPLACEMENT);
    haveWindowedPlacement = GetWindowPlacement(hwnd, &windowedPlacement) != 0;
    SetWindowLongPtrW(hwnd, GWL_STYLE, windowedStyle & ~static_cast<LONG_PTR>(WS_OVERLAPPEDWINDOW));
    SetWindowPos(hwnd, HWND_TOPMOST, mi.rcMonitor.left, mi.rcMonitor.top,
        mi.rcMonitor.right - mi.rcMonitor.left, mi.rcMonitor.bottom - mi.rcMonitor.top,
        SWP_FRAMECHANGED | SWP_SHOWWINDOW);
    isFullscreen = true;
    InvalidateRect(hwnd, nullptr, FALSE);
}

static void exitFullscreen(HWND hwnd) {
    if (!isFullscreen) return;
    SetWindowLongPtrW(hwnd, GWL_STYLE, windowedStyle);
    SetWindowLongPtrW(hwnd, GWL_EXSTYLE, windowedExStyle);
    const HWND z = (windowedExStyle & WS_EX_TOPMOST) ? HWND_TOPMOST : HWND_NOTOPMOST;
    SetWindowPos(hwnd, z, 0, 0, 0, 0,
        SWP_NOMOVE | SWP_NOSIZE | SWP_FRAMECHANGED | SWP_NOACTIVATE);
    if (haveWindowedPlacement) SetWindowPlacement(hwnd, &windowedPlacement);
    isFullscreen = false;
    InvalidateRect(hwnd, nullptr, FALSE);
}

static void resizeForUvc(HWND hwnd) {
    const auto info = currentMonitorInfo(hwnd);
    // On a physical Full HD monitor, HDMI 1920x1080 is pixel-perfect fullscreen.
    if (monitorIsPhysicalFhd(info)) {
        enterFullscreen(hwnd);
        return;
    }
    // On other monitors prefer an exact 1920x1080 client area. When the work
    // area is smaller, fit within it to keep controls and titlebar accessible.
    if (isFullscreen) exitFullscreen(hwnd);
    if (IsIconic(hwnd) || IsZoomed(hwnd)) ShowWindow(hwnd, SW_RESTORE);
    const RECT work = currentMonitorInfo(hwnd).rcWork;
    const int availableW = (std::max)(1L, work.right - work.left);
    const int availableH = (std::max)(1L, work.bottom - work.top);
    const DWORD style = static_cast<DWORD>(GetWindowLongPtrW(hwnd, GWL_STYLE));
    const DWORD exStyle = static_cast<DWORD>(GetWindowLongPtrW(hwnd, GWL_EXSTYLE));
    RECT border{0,0,0,0};
    AdjustWindowRectEx(&border, style, GetMenu(hwnd) != nullptr, exStyle);
    const int chromeW = border.right - border.left;
    const int chromeH = border.bottom - border.top;
    const double fit = (std::min)(1.0, (std::min)(
        double((std::max)(1, availableW - chromeW)) / 1920,
        double((std::max)(1, availableH - chromeH)) / 1080));
    const int outerW = (std::min)(availableW, (std::max)(1, int(1920 * fit) + chromeW));
    const int outerH = (std::min)(availableH, (std::max)(1, int(1080 * fit) + chromeH));
    const int x = work.left + (availableW - outerW) / 2;
    const int y = work.top + (availableH - outerH) / 2;
    SetWindowPos(hwnd, HWND_NOTOPMOST, x, y, outerW, outerH,
        SWP_FRAMECHANGED | SWP_SHOWWINDOW);
    InvalidateRect(hwnd, nullptr, FALSE);
}

static LRESULT CALLBACK windowProc(HWND hwnd, UINT message, WPARAM w, LPARAM l) {
    switch (message) {
    case WM_SOURCE_MODE:
        if (w == 1) clearPendingPinch(hwnd);
        // Ignore metadata queued by an already-disconnected TCP session.
        if (w == 1 && static_cast<std::uint64_t>(l) == sessionGeneration.load())
            resizeForUvc(hwnd);
        return 0;
    case WM_KEYDOWN:
        if (w == VK_F11) {
            if ((static_cast<unsigned long long>(l) & (1ULL << 30)) == 0) {
                if (isFullscreen) exitFullscreen(hwnd);
                else enterFullscreen(hwnd);
            }
            return 0;
        }
        // Do not forward Windows shortcuts such as Alt+R or F11. Keyboard
        // actions apply only while Android itself has been opted into control.
        // Android remains the sole authority for granting remote input.
        if (remoteControlEnabled.load() && linkConnected.load() &&
            streamSource.load() == 2) {
            int key = 0;
            switch (w) {
            case VK_BACK: key = 1; break;
            case VK_DELETE: key = 2; break;
            case VK_RETURN: key = 3; break;
            case VK_LEFT: key = 4; break;
            case VK_RIGHT: key = 5; break;
            case VK_HOME: key = 6; break;
            case VK_END: key = 7; break;
            }
            if (key) { enqueueKeyboardKey(key); return 0; }
        }
        break;
    case WM_CHAR: {
        // WM_CHAR delivers UTF-16 committed IME output, not composition
        // keystrokes. Buffer surrogate pairs before UTF-8 encoding.
        const std::uint32_t unit = static_cast<std::uint32_t>(w);
        if (unit >= 0xd800 && unit <= 0xdbff) {
            pendingHighSurrogate = static_cast<wchar_t>(unit);
            return 0;
        }
        if (unit >= 0xdc00 && unit <= 0xdfff) {
            if (pendingHighSurrogate) {
                const std::uint32_t cp = 0x10000u +
                    ((static_cast<std::uint32_t>(pendingHighSurrogate) - 0xd800u) << 10) +
                    (unit - 0xdc00u);
                pendingHighSurrogate = 0;
                enqueueKeyboardCodepoint(cp);
            }
            return 0;
        }
        pendingHighSurrogate = 0;
        if (unit >= 0x20 && unit != 0x7f) {
            enqueueKeyboardCodepoint(unit);
            return 0;
        }
        break;
    }
    case WM_APP: {
        refreshPosted.store(false);
        // Frequent frames should only invalidate; modifying the window title
        // and formatting diagnostics 30 times a second adds UI contention.
        static ULONGLONG lastTitleUpdate = 0;
        const ULONGLONG now = GetTickCount64();
        if (w == 1 || now - lastTitleUpdate >= 1000) {
            lastTitleUpdate = now;
            RECT client{};
            GetClientRect(hwnd, &client);
            std::wstring title = L"My Cast Receiver 031 | ";
            title += linkConnected.load() ? L"Android接続中" : L"Android未接続・接続待機中";
            {
                std::lock_guard<std::mutex> guard(lockFrame);
                if (picture && linkConnected.load()) {
                    title += L" | 映像 " + std::to_wstring(picture->width) + L" x " + std::to_wstring(picture->height);
                    const double scale = (std::min)(double(client.right)/picture->width, double(client.bottom)/picture->height);
                    title += L" | 表示 " + std::to_wstring(int(picture->width * scale)) + L" x " + std::to_wstring(int(picture->height * scale));
                }
            }
            title += L" | ウィンドウ " + std::to_wstring(client.right) + L" x " + std::to_wstring(client.bottom);
            SetPropW(hwnd, L"MyCastDecodedFrames", reinterpret_cast<HANDLE>(static_cast<ULONG_PTR>(decodedFrames.load())));
            SetPropW(hwnd, L"MyCastRejectedFrames", reinterpret_cast<HANDLE>(static_cast<ULONG_PTR>(rejectedFrames.load())));
            SetWindowTextW(hwnd, title.c_str());
        }
        InvalidateRect(hwnd, nullptr, FALSE);
        return 0;
    }
    case WM_LBUTTONDOWN:
        if (remoteControlEnabled.load() && GetTickCount64()-lastTouchMs > 700) {
            dragStart={GET_X_LPARAM(l),GET_Y_LPARAM(l)};
            dragLast=dragStart;
            dragStarted=GetTickCount64();
            mouseDragging=true;
            SetCapture(hwnd);
        }
        return 0;
    case WM_MOUSEMOVE:
        if (mouseDragging) dragLast={GET_X_LPARAM(l),GET_Y_LPARAM(l)};
        return 0;
    case WM_LBUTTONUP:
        if (mouseDragging) {
            if (GetTickCount64()-lastTouchMs > 700) {
                dragLast={GET_X_LPARAM(l),GET_Y_LPARAM(l)};
                const ULONGLONG elapsed=GetTickCount64()-dragStarted;
                const int dist=abs(dragLast.x-dragStart.x)+abs(dragLast.y-dragStart.y);
                enqueueControl(hwnd,dist>12?3:elapsed>=500?2:1,dragStart,dragLast);
            }
            // Touch-synthesized mouse events may suppress a click, but must never
            // suppress releasing Windows mouse capture.
            mouseDragging=false;
            ReleaseCapture();
        }
        return 0;
    case WM_XBUTTONUP: {
        const int button=GET_XBUTTON_WPARAM(w);
        if (button == XBUTTON1 || button == XBUTTON2) {
            POINT point{GET_X_LPARAM(l),GET_Y_LPARAM(l)};
            enqueueControl(hwnd, button == XBUTTON1 ? 6 : 7, point, point);
            return TRUE;
        }
        break;
    }
    case WM_RBUTTONUP: {
        POINT point{GET_X_LPARAM(l),GET_Y_LPARAM(l)};
        if (GetTickCount64()-lastTouchMs > 700) enqueueControl(hwnd,5,point,point);
        return 0;
    }
    case WM_MOUSEWHEEL: {
        POINT point{GET_X_LPARAM(l),GET_Y_LPARAM(l)};
        ScreenToClient(hwnd,&point);
        const int delta=GET_WHEEL_DELTA_WPARAM(w);
        // MK_CONTROL comes from the wheel message; GetKeyState alone can
        // miss modifier state when receiving redirected/synthetic input.
        if ((GET_KEYSTATE_WPARAM(w) & MK_CONTROL) || (GetKeyState(VK_CONTROL)&0x8000)) {
            if (!remoteControlEnabled.load() || !linkConnected.load()) return 0;
            pinchAnchor = point;
            pinchWheelRemainder += delta;
            const int detents = pinchWheelRemainder / WHEEL_DELTA;
            pinchWheelRemainder -= detents * WHEEL_DELTA;
            pinchPendingDetents = (std::clamp)(pinchPendingDetents + detents, -24, 24);
            if (pinchPendingDetents && !pinchTimerArmed) {
                pinchTimerArmed = SetTimer(hwnd, PINCH_TIMER_ID, PINCH_BATCH_MS, nullptr) != 0;
                if (!pinchTimerArmed) flushPinchWheel(hwnd);
            }
        } else {
            // Preserve v028 one-wheel-event scrolling for normal wheel input.
            POINT end=point;
            end.y+=delta>0?120:-120;
            enqueueControl(hwnd,3,point,end);
        }
        return 0;
    }
    case WM_POINTERDOWN:
    case WM_POINTERUPDATE:
    case WM_POINTERUP: {
        const UINT32 id=GET_POINTERID_WPARAM(w);
        POINTER_INFO info{};
        if (GetPointerInfo(id,&info) && info.pointerType == PT_TOUCH) {
            lastTouchMs=GetTickCount64();
            POINT pt=info.ptPixelLocation;
            ScreenToClient(hwnd,&pt);
            if (message==WM_POINTERDOWN) {
                touchStart[id]=pt; touchEnd[id]=pt;
            } else if(touchStart.count(id)) {
                touchEnd[id]=pt;
                if (message==WM_POINTERUP) {
                    if (touchStart.size()>=2) {
                        auto it=touchStart.begin(); auto it2=std::next(it);
                        enqueueControl(hwnd,4,it->second,touchEnd[it->first],it2->second,touchEnd[it2->first]);
                        touchStart.clear(); touchEnd.clear();
                    } else {
                        POINT start=touchStart[id], finish=touchEnd[id];
                        enqueueControl(hwnd,(abs(start.x-finish.x)+abs(start.y-finish.y)>12)?3:1,start,finish);
                        touchStart.erase(id);touchEnd.erase(id);
                    }
                }
            }
            return 0;
        }
        break;
    }
    case WM_CAPTURECHANGED:
        mouseDragging=false;
        return 0;
    case WM_GETMINMAXINFO: {
        auto* info = reinterpret_cast<MINMAXINFO*>(l);
        info->ptMaxTrackSize.x = (std::max)(info->ptMaxTrackSize.x, 4096L);
        info->ptMaxTrackSize.y = (std::max)(info->ptMaxTrackSize.y, 4096L);
        return 0;
    }
    case WM_ERASEBKGND: return 1;
    case WM_PAINT: {
        const ULONGLONG start = GetTickCount64();
        PAINTSTRUCT ps;
        HDC dc = BeginPaint(hwnd, &ps);
        RECT bounds{};
        GetClientRect(hwnd, &bounds);
        const int clientW = bounds.right, clientH = bounds.bottom;
        std::shared_ptr<DecodedFrame> frame;
        {
            std::lock_guard<std::mutex> guard(lockFrame);
            frame = picture;
        }
        if (clientW > 0 && clientH > 0) {
            if (linkConnected.load() && frame && frame->bitmap && frame->width && frame->height) {
                const double scale = (std::min)(double(clientW)/frame->width, double(clientH)/frame->height);
                const int w = static_cast<int>(frame->width * scale);
                const int h = static_cast<int>(frame->height * scale);
                const int x = (clientW-w)/2, y = (clientH-h)/2;
                HDC src = CreateCompatibleDC(dc);
                if (src) {
                    HGDIOBJ old = SelectObject(src, frame->bitmap);
                    // GDI's native BitBlt/StretchBlt path avoids the old
                    // GDI+ DrawImage and temporary full-screen back buffer.
                    if (w == int(frame->width) && h == int(frame->height))
                        BitBlt(dc, x, y, w, h, src, 0, 0, SRCCOPY);
                    else {
                        SetStretchBltMode(dc, COLORONCOLOR);
                        StretchBlt(dc, x, y, w, h, src, 0, 0,
                            int(frame->width), int(frame->height), SRCCOPY);
                    }
                    SelectObject(src, old);
                    DeleteDC(src);
                }
                HBRUSH black = static_cast<HBRUSH>(GetStockObject(BLACK_BRUSH));
                if (y > 0) {
                    RECT top{0,0,clientW,y};
                    RECT bottom{0,y+h,clientW,clientH};
                    FillRect(dc, &top, black); FillRect(dc, &bottom, black);
                }
                if (x > 0) {
                    RECT left{0,y,x,y+h};
                    RECT right{x+w,y,clientW,y+h};
                    FillRect(dc, &left, black); FillRect(dc, &right, black);
                }
            } else {
                FillRect(dc, &bounds, static_cast<HBRUSH>(GetStockObject(BLACK_BRUSH)));
                SetBkMode(dc, TRANSPARENT);
                SetTextColor(dc, RGB(210,210,210));
                const wchar_t* text = L"Android未接続\n\nスマートフォンでMyMonitorを起動してください。\n同じWi-Fiに接続すると、自動で接続します。\n\nAndroid画面を映すには、スマートフォンで画面共有を許可してください。\nF11：全画面表示・解除";
                DrawTextW(dc, text, -1, &bounds, DT_CENTER | DT_WORDBREAK);
            }
            if (!linkConnected.load()) {
                RECT label{0, 0, (std::min)(clientW, 540), 34};
                HBRUSH dim = CreateSolidBrush(RGB(50, 36, 15));
                FillRect(dc, &label, dim);
                DeleteObject(dim);
                SetBkMode(dc, TRANSPARENT);
                SetTextColor(dc, RGB(255, 221, 137));
                DrawTextW(dc, L"Android未接続・自動接続を待っています", -1,
                    &label, DT_SINGLELINE | DT_CENTER | DT_VCENTER);
            }
        }
        EndPaint(hwnd, &ps);
        lastPaintMs.store(static_cast<unsigned long>(GetTickCount64() - start));
        return 0;
    }
    case WM_TIMER:
        if (w == PINCH_TIMER_ID) {
            KillTimer(hwnd, PINCH_TIMER_ID);
            pinchTimerArmed = false;
            flushPinchWheel(hwnd);
        }
        if (w == 15) queueRefresh(); // periodic diagnostics and disconnected UI
        return 0;
    case WM_SIZE: SendMessageW(hwnd, WM_APP, 1, 0); InvalidateRect(hwnd, nullptr, FALSE); return 0;
    case WM_CLOSE: clearPendingPinch(hwnd); KillTimer(hwnd, 15); DestroyWindow(hwnd); return 0;
    case WM_DESTROY: PostQuitMessage(0); return 0;
    }
    return DefWindowProc(hwnd, message, w, l);
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int show) {
    WSADATA ws; if (WSAStartup(MAKEWORD(2,2), &ws)) return 1;
    ULONG_PTR token; GdiplusStartupInput input; if (GdiplusStartup(&token, &input, nullptr) != Ok) { WSACleanup(); return 1; }
    listener = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    sockaddr_in addr{}; addr.sin_family = AF_INET; addr.sin_port = htons(57007); addr.sin_addr.s_addr = INADDR_ANY;
    if (listener == INVALID_SOCKET || bind(listener, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) || listen(listener, 1)) {
        MessageBox(nullptr, L"接続を待ち受けできません。起動済みのMy Cast Receiverを閉じて、もう一度起動してください。", L"My Cast Receiver", MB_ICONERROR);
        if(listener != INVALID_SOCKET) closesocket(listener);
        GdiplusShutdown(token); WSACleanup(); return 1;
    }
    WNDCLASS wc{}; wc.lpfnWndProc = windowProc; wc.hInstance = instance; wc.lpszClassName = L"MyCastReceiver019"; wc.hCursor = LoadCursor(nullptr, IDC_ARROW);
    wc.hIcon = LoadIcon(instance, MAKEINTRESOURCE(101));
    RegisterClass(&wc);
    // Actual VIDEO CLIENT AREA must be 1920x1080, excluding the window chrome.
    // User resizing remains possible for smaller screens.
    RECT videoRect{0, 0, 1920, 1080};
    AdjustWindowRectEx(&videoRect, WS_OVERLAPPEDWINDOW, FALSE, 0);
    windowHandle = CreateWindow(wc.lpszClassName, L"My Cast Receiver", WS_OVERLAPPEDWINDOW,
        0, 0, videoRect.right-videoRect.left, videoRect.bottom-videoRect.top,
        nullptr, nullptr, instance, nullptr);
    if (!windowHandle) { closesocket(listener); GdiplusShutdown(token); WSACleanup(); return 1; }
    SendMessage(windowHandle, WM_SETICON, ICON_BIG, reinterpret_cast<LPARAM>(LoadImage(instance, MAKEINTRESOURCE(101), IMAGE_ICON, 48, 48, LR_DEFAULTCOLOR)));
    SendMessage(windowHandle, WM_SETICON, ICON_SMALL, reinterpret_cast<LPARAM>(LoadImage(instance, MAKEINTRESOURCE(101), IMAGE_ICON, 16, 16, LR_DEFAULTCOLOR)));
    ShowWindow(windowHandle, show);
    SetTimer(windowHandle, 15, 1000, nullptr);
    decodeWorker = std::thread(decodeLoop);
    worker = std::thread(receiveLoop);
    discoveryWorker = std::thread(discoveryLoop);
    audioWorker = std::thread(audioLoop);
    MSG msg; while (GetMessage(&msg, nullptr, 0, 0) > 0) { TranslateMessage(&msg); DispatchMessage(&msg); }
    alive = false;
    closesocket(listener);
    if (audioListener != INVALID_SOCKET) closesocket(audioListener);
    { std::lock_guard<std::mutex> g(lockSocket); if (peer != INVALID_SOCKET) shutdown(peer, SD_BOTH); }
    worker.join();
    pendingCV.notify_all();
    decodeWorker.join();
    discoveryWorker.join();
    audioWorker.join();
    picture.reset(); GdiplusShutdown(token); WSACleanup(); return 0;
}
