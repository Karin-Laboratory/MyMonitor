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
using namespace Gdiplus;
static HWND windowHandle;
static std::mutex lockFrame;
static std::unique_ptr<Bitmap> picture;
static std::atomic<bool> alive{true};
static SOCKET listener = INVALID_SOCKET;
static SOCKET peer = INVALID_SOCKET;
static std::mutex lockSocket;
static std::thread worker;
static bool readExact(SOCKET s, char* dst, int size) {
    while (size > 0 && alive) { int n = recv(s, dst, size, 0); if (n <= 0) return false; dst += n; size -= n; }
    return size == 0;
}
static void clearPicture() {
    { std::lock_guard<std::mutex> g(lockFrame); picture.reset(); }
    PostMessage(windowHandle, WM_APP, 0, 0);
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
            IStream* stream = SHCreateMemStream(bytes.data(), n);
            if (!stream) break;
            {
                Bitmap decoded(stream);
                if (decoded.GetLastStatus() == Ok && decoded.GetWidth() <= 4096 && decoded.GetHeight() <= 4096) {
                    std::unique_ptr<Bitmap> copy(decoded.Clone(0, 0, decoded.GetWidth(), decoded.GetHeight(), PixelFormat32bppRGB));
                    if (copy && copy->GetLastStatus() == Ok) {
                        std::lock_guard<std::mutex> g(lockFrame); picture = std::move(copy);
                    } else valid = false;
                } else valid = false;
            }
            stream->Release();
            if (!valid) break;
            PostMessage(windowHandle, WM_APP, 0, 0);
            const char ack = 0x06;
            if (send(s, &ack, 1, 0) != 1) break;
        }
        { std::lock_guard<std::mutex> g(lockSocket); closesocket(s); peer = INVALID_SOCKET; }
        clearPicture();
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
        static std::wstring lastTitle;
        if (title != lastTitle) { SetWindowText(hwnd, title.c_str()); lastTitle = title; }
        InvalidateRect(hwnd, nullptr, FALSE); return 0;
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
                        wchar_t text[] = L"My Cast Receiver 009\nWaiting on TCP 57007\n\nPixel: Windows IPv4 address + port 57007\nOBS: Window Capture -> My Cast Receiver\nFull HD / Video only";
                        DrawText(back, text, -1, &r, DT_CENTER | DT_WORDBREAK);
                    }
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
    WNDCLASS wc{}; wc.lpfnWndProc = windowProc; wc.hInstance = instance; wc.lpszClassName = L"MyCastReceiver009"; wc.hCursor = LoadCursor(nullptr, IDC_ARROW);
    wc.hIcon = LoadIcon(instance, MAKEINTRESOURCE(101));
    RegisterClass(&wc);
    windowHandle = CreateWindow(wc.lpszClassName, L"My Cast Receiver", WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT, 1280, 760, nullptr, nullptr, instance, nullptr);
    if (!windowHandle) { closesocket(listener); GdiplusShutdown(token); WSACleanup(); return 1; }
    SendMessage(windowHandle, WM_SETICON, ICON_BIG, reinterpret_cast<LPARAM>(LoadImage(instance, MAKEINTRESOURCE(101), IMAGE_ICON, 48, 48, LR_DEFAULTCOLOR)));
    SendMessage(windowHandle, WM_SETICON, ICON_SMALL, reinterpret_cast<LPARAM>(LoadImage(instance, MAKEINTRESOURCE(101), IMAGE_ICON, 16, 16, LR_DEFAULTCOLOR)));
    ShowWindow(windowHandle, show);
    worker = std::thread(receiveLoop);
    MSG msg; while (GetMessage(&msg, nullptr, 0, 0) > 0) { TranslateMessage(&msg); DispatchMessage(&msg); }
    alive = false;
    closesocket(listener);
    { std::lock_guard<std::mutex> g(lockSocket); if (peer != INVALID_SOCKET) shutdown(peer, SD_BOTH); }
    worker.join();
    picture.reset(); GdiplusShutdown(token); WSACleanup(); return 0;
}
