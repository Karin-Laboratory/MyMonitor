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
using namespace Gdiplus;
static HWND windowHandle;
static std::mutex lockFrame;
static std::unique_ptr<Bitmap> picture;
static std::atomic<bool> linkConnected{false};
static std::atomic<unsigned long> rejectedFrames{0};
static std::atomic<bool> alive{true};
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
// Preserve the last valid image through transient disconnections.
// It remains visibly marked as stale until a new frame arrives.
static void updateLink(bool connected) {
    linkConnected.store(connected);
    PostMessage(windowHandle, WM_APP, 0, 0);
}
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
    const char request[] = "MYMONITOR_DISCOVER_V1";
    const char response[] = "MYCAST_RECEIVER_V1|57007";
    while (alive) {
        char buffer[128] = {};
        sockaddr_in from{};
        int fromSize = sizeof(from);
        int size = recvfrom(s, buffer, sizeof(buffer), 0, reinterpret_cast<sockaddr*>(&from), &fromSize);
        if (size == sizeof(request)-1 && std::memcmp(buffer, request, sizeof(request)-1) == 0) {
            sendto(s, response, sizeof(response)-1, 0, reinterpret_cast<sockaddr*>(&from), fromSize);
        }
    }
    closesocket(s);
    discoverySocket = INVALID_SOCKET;
}

static void receiveLoop() {
    while (alive) {
        SOCKET s = accept(listener, nullptr, nullptr);
        if (s == INVALID_SOCKET) break;
        { std::lock_guard<std::mutex> g(lockSocket); if (!alive) { closesocket(s); break; } peer = s; }
        BOOL noDelay = TRUE;
        setsockopt(s, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<char*>(&noDelay), sizeof(noDelay));
        DWORD timeout = 5000;
        setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<char*>(&timeout), sizeof(timeout));
        setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, reinterpret_cast<char*>(&timeout), sizeof(timeout));
        char magic[4];
        bool valid = readExact(s, magic, 4) && memcmp(magic, "MMC9", 4) == 0;
        while (alive && valid) {
            uint32_t networkLength;
            if (!readExact(s, reinterpret_cast<char*>(&networkLength), 4)) break;
            uint32_t n = ntohl(networkLength);
            if (n == 0 || n > 8 * 1024 * 1024) break;
            std::vector<unsigned char> bytes(n);
            if (!readExact(s, reinterpret_cast<char*>(bytes.data()), static_cast<int>(n))) break;
            // A single malformed/incomplete MJPEG frame must NOT tear down the TCP
            // session. The USB grabber can occasionally emit one bad JPEG.
            // ACK that packet so Android can immediately send its newest frame.
            bool imageReady = false;
            IStream* stream = SHCreateMemStream(bytes.data(), n);
            if (stream) {
                {
                    Bitmap decoded(stream);
                    if (decoded.GetLastStatus() == Ok &&
                        decoded.GetWidth() > 0 && decoded.GetHeight() > 0 &&
                        decoded.GetWidth() <= 4096 && decoded.GetHeight() <= 4096) {
                        std::unique_ptr<Bitmap> copy(decoded.Clone(0, 0, decoded.GetWidth(),
                            decoded.GetHeight(), PixelFormat32bppRGB));
                        if (copy && copy->GetLastStatus() == Ok) {
                            std::lock_guard<std::mutex> g(lockFrame);
                            picture = std::move(copy);
                            imageReady = true;
                        }
                    }
                }
                stream->Release();
            }
            if (!imageReady) rejectedFrames.fetch_add(1);
            else linkConnected.store(true);
            PostMessage(windowHandle, WM_APP, 0, 0);
            const char ack = 0x06;
            if (send(s, &ack, 1, 0) != 1) break;
        }
        { std::lock_guard<std::mutex> g(lockSocket); closesocket(s); peer = INVALID_SOCKET; }
        updateLink(false);
    }
}
static LRESULT CALLBACK windowProc(HWND hwnd, UINT message, WPARAM w, LPARAM l) {
    switch (message) {
    case WM_APP: {
        std::wstring title = L"My Cast Receiver";
        {
            std::lock_guard<std::mutex> g(lockFrame);
            if (picture) title += L" - " + std::to_wstring(picture->GetWidth()) + L" x " + std::to_wstring(picture->GetHeight());
        }
        if (!linkConnected.load()) title += L" [reconnecting / last frame]";
        auto bad = rejectedFrames.load();
        if (bad) title += L" [skipped " + std::to_wstring(bad) + L" bad frames]";
        static std::wstring lastTitle;
        if (title != lastTitle) { SetWindowText(hwnd, title.c_str()); lastTitle = title; }
        InvalidateRect(hwnd, nullptr, FALSE); return 0;
    }
    case WM_GETMINMAXINFO: {
        // Windows defaults max tracking size to current display dimensions.
        // The capture surface may be bigger than the desktop (e.g. a headless
        // GitHub runner or a 1080p screen with window decorations).
        auto* info = reinterpret_cast<MINMAXINFO*>(l);
        info->ptMaxTrackSize.x = (std::max)(info->ptMaxTrackSize.x, 4096L);
        info->ptMaxTrackSize.y = (std::max)(info->ptMaxTrackSize.y, 4096L);
        return 0;
    }
    case WM_ERASEBKGND: return 1;
    case WM_PAINT: {
        PAINTSTRUCT ps; HDC dc = BeginPaint(hwnd, &ps); RECT r; GetClientRect(hwnd, &r);
        if (r.right > 0 && r.bottom > 0) {
            // Paint the complete frame offscreen. Only one BitBlt touches the window,
            // so OBS and the desktop never see a black-clear intermediate frame.
            HDC back = CreateCompatibleDC(dc);
            HBITMAP bitmap = CreateCompatibleBitmap(dc, r.right, r.bottom);
            if (back && bitmap) {
                HGDIOBJ previous = SelectObject(back, bitmap);
                FillRect(back, &r, static_cast<HBRUSH>(GetStockObject(BLACK_BRUSH)));
                {
                    std::lock_guard<std::mutex> g(lockFrame);
                    if (picture) {
                        Graphics graphics(back);
                        graphics.SetInterpolationMode(InterpolationModeBilinear);
                        double scale = (std::min)(double(r.right) / picture->GetWidth(), double(r.bottom) / picture->GetHeight());
                        int width = int(picture->GetWidth() * scale), height = int(picture->GetHeight() * scale);
                        graphics.DrawImage(picture.get(), (r.right-width)/2, (r.bottom-height)/2, width, height);
                        graphics.Flush(FlushIntentionSync);
                    } else {
                        SetBkMode(back, TRANSPARENT); SetTextColor(back, RGB(210,210,210));
                        wchar_t text[] = L"My Cast Receiver 012\nWaiting on TCP 57007\n\nPixel: Auto discover or IPv4 + port 57007\nOBS: Window Capture -> My Cast Receiver\nFull HD / Video only";
                        DrawText(back, text, -1, &r, DT_CENTER | DT_WORDBREAK);
                    }
                }
                if (!linkConnected.load()) {
                    // Make stale frames explicit in OBS instead of silently holding them.
                    RECT labelRect{0, 0, (std::min)(r.right, 540L), 34};
                    HBRUSH dim = CreateSolidBrush(RGB(50, 36, 15));
                    FillRect(back, &labelRect, dim);
                    DeleteObject(dim);
                    SetBkMode(back, TRANSPARENT);
                    SetTextColor(back, RGB(255, 221, 137));
                    DrawText(back, L"Connection lost - waiting for automatic reconnect", -1,
                        &labelRect, DT_SINGLELINE | DT_CENTER | DT_VCENTER);
                }
                BitBlt(dc, 0, 0, r.right, r.bottom, back, 0, 0, SRCCOPY);
                SelectObject(back, previous);
            }
            if (bitmap) DeleteObject(bitmap);
            if (back) DeleteDC(back);
        }
        EndPaint(hwnd, &ps); return 0;
    }
    case WM_SIZE: InvalidateRect(hwnd, nullptr, FALSE); return 0;
    case WM_CLOSE: DestroyWindow(hwnd); return 0;
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
    WNDCLASS wc{}; wc.lpfnWndProc = windowProc; wc.hInstance = instance; wc.lpszClassName = L"MyCastReceiver012"; wc.hCursor = LoadCursor(nullptr, IDC_ARROW);
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
    worker = std::thread(receiveLoop);
    discoveryWorker = std::thread(discoveryLoop);
    MSG msg; while (GetMessage(&msg, nullptr, 0, 0) > 0) { TranslateMessage(&msg); DispatchMessage(&msg); }
    alive = false;
    closesocket(listener);
    { std::lock_guard<std::mutex> g(lockSocket); if (peer != INVALID_SOCKET) shutdown(peer, SD_BOTH); }
    worker.join();
    discoveryWorker.join();
    picture.reset(); GdiplusShutdown(token); WSACleanup(); return 0;
}
