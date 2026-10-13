#ifndef UNICODE
#define UNICODE
#endif
#define _UNICODE
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
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
using namespace Gdiplus;
static HWND windowHandle;
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
        if (!readOk || std::memcmp(magic, "MMC9", 4) != 0) {
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
            if (n > 8 * 1024 * 1024) break;
            if (n == 0) {
                // v014 and later keepalive. No fake image is shown.
                const char ack = 0x06;
                if (send(s, &ack, 1, 0) != 1) break;
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
            const char ack = 0x06;
            if (send(s, &ack, 1, 0) != 1) break;
        }
        // Keep the last displayed frame; reject decode from a closed session.
        sessionGeneration.fetch_add(1);
        {
            std::lock_guard<std::mutex> guard(lockSocket);
            closesocket(s);
            peer = INVALID_SOCKET;
        }
        updateLink(false);
    }
}

static LRESULT CALLBACK windowProc(HWND hwnd, UINT message, WPARAM w, LPARAM l) {
    switch (message) {
    case WM_APP: {
        refreshPosted.store(false);
        // Frequent frames should only invalidate; modifying the window title
        // and formatting diagnostics 30 times a second adds UI contention.
        static ULONGLONG lastTitleUpdate = 0;
        const ULONGLONG now = GetTickCount64();
        if (now - lastTitleUpdate >= 1000) {
            lastTitleUpdate = now;
            std::wstring title = L"My Cast Receiver 015";
            {
                std::lock_guard<std::mutex> guard(lockFrame);
                if (picture) title += L" - " + std::to_wstring(picture->width) +
                    L" x " + std::to_wstring(picture->height);
            }
            if (!linkConnected.load()) title += L" [reconnecting / last frame]";
            const auto rejected = rejectedFrames.load();
            if (rejected) title += L" [skipped " + std::to_wstring(rejected) + L" bad frames]";
            title += L" [rx " + std::to_wstring(lastReceiveMs.load()) +
                L"ms, decode " + std::to_wstring(lastDecodeMs.load()) +
                L"ms, draw " + std::to_wstring(lastPaintMs.load()) +
                L"ms, decoded " + std::to_wstring(decodedFrames.load()) +
                L", dropped " + std::to_wstring(droppedFrames.load()) + L"]";
            SetWindowTextW(hwnd, title.c_str());
        }
        InvalidateRect(hwnd, nullptr, FALSE);
        return 0;
    }
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
            if (frame && frame->bitmap && frame->width && frame->height) {
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
                const wchar_t* text = L"My Cast Receiver 015\nWaiting on TCP 57007\n\n"
                    L"Android: auto discovery or IPv4 + port 57007\n"
                    L"OBS: Window Capture -> My Cast Receiver\nFull HD / Screen + audio / UVC";
                DrawTextW(dc, text, -1, &bounds, DT_CENTER | DT_WORDBREAK);
            }
            if (!linkConnected.load()) {
                RECT label{0, 0, (std::min)(clientW, 540), 34};
                HBRUSH dim = CreateSolidBrush(RGB(50, 36, 15));
                FillRect(dc, &label, dim);
                DeleteObject(dim);
                SetBkMode(dc, TRANSPARENT);
                SetTextColor(dc, RGB(255, 221, 137));
                DrawTextW(dc, L"Connection lost - waiting for automatic reconnect", -1,
                    &label, DT_SINGLELINE | DT_CENTER | DT_VCENTER);
            }
        }
        EndPaint(hwnd, &ps);
        lastPaintMs.store(static_cast<unsigned long>(GetTickCount64() - start));
        return 0;
    }
    case WM_TIMER:
        if (w == 15) queueRefresh(); // periodic diagnostics and disconnected UI
        return 0;
    case WM_SIZE: InvalidateRect(hwnd, nullptr, FALSE); return 0;
    case WM_CLOSE: KillTimer(hwnd, 15); DestroyWindow(hwnd); return 0;
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
        MessageBox(nullptr, L"Cannot listen on TCP 57007. Close another receiver and retry.", L"My Cast Receiver", MB_ICONERROR);
        if(listener != INVALID_SOCKET) closesocket(listener);
        GdiplusShutdown(token); WSACleanup(); return 1;
    }
    WNDCLASS wc{}; wc.lpfnWndProc = windowProc; wc.hInstance = instance; wc.lpszClassName = L"MyCastReceiver015"; wc.hCursor = LoadCursor(nullptr, IDC_ARROW);
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
