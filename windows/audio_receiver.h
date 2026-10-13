// MyCast v013: low-latency internal-audio receiver, PCM16LE 48kHz stereo.
// Dedicated TCP 57009; independent from the legacy MMC9 MJPEG video stream.
// Windows winmm waveOut is in-box, keeping the receiver a single executable.
#pragma once
#include <mmsystem.h>
#include <array>
#include <vector>

static SOCKET audioListener = INVALID_SOCKET;
static std::thread audioWorker;

static void audioLoop() {
    audioListener = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (audioListener == INVALID_SOCKET) return;
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(57009);
    if (bind(audioListener, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0 ||
        listen(audioListener, 2) != 0) {
        closesocket(audioListener);
        audioListener = INVALID_SOCKET;
        return;
    }
    while (alive) {
        SOCKET s = accept(audioListener, nullptr, nullptr);
        if (s == INVALID_SOCKET) break;
        DWORD timeout = 5000;
        setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<char*>(&timeout), sizeof(timeout));
        char magic[4];
        if (!readExact(s, magic, 4) || std::memcmp(magic, "MMA1", 4) != 0) {
            closesocket(s); continue;
        }
        // fixed audio contract; avoid an untrusted peer allocating huge buffers
        WAVEFORMATEX fmt{};
        fmt.wFormatTag = WAVE_FORMAT_PCM;
        fmt.nChannels = 2;
        fmt.nSamplesPerSec = 48000;
        fmt.wBitsPerSample = 16;
        fmt.nBlockAlign = 4;
        fmt.nAvgBytesPerSec = 48000 * 4;
        HWAVEOUT device = nullptr;
        if (waveOutOpen(&device, WAVE_MAPPER, &fmt, 0, 0, CALLBACK_NULL) != MMSYSERR_NOERROR)
            device = nullptr; // Windows CI may have no physical audio device.
        ULONGLONG lastOpenAttempt = GetTickCount64();
        struct Slot {
            WAVEHDR header{};
            std::vector<char> data;
            bool prepared = false;
        };
        std::array<Slot, 4> slots;
        size_t at = 0;
        while (alive) {
            uint32_t networkSize = 0;
            if (!readExact(s, reinterpret_cast<char*>(&networkSize), 4)) break;
            uint32_t bytes = ntohl(networkSize);
            if (bytes == 0 || bytes > 38400 || bytes % 4) break;
            std::vector<char> pcm(bytes);
            if (!readExact(s, pcm.data(), bytes)) break;
            // Audio devices can disappear/reappear after a PC locks or audio
            // outputs switch. Keep video alive and retry only the audio sink.
            if (!device && GetTickCount64() - lastOpenAttempt > 1000) {
                lastOpenAttempt = GetTickCount64();
                if (waveOutOpen(&device, WAVE_MAPPER, &fmt, 0, 0, CALLBACK_NULL) != MMSYSERR_NOERROR)
                    device = nullptr;
            }
            if (!device) continue;
            Slot &slot = slots[at++ % slots.size()];
            const ULONGLONG slotWaitStart = GetTickCount64();
            while (alive && slot.prepared && !(slot.header.dwFlags & WHDR_DONE)) {
                if (GetTickCount64() - slotWaitStart > 800) {
                    // A blocked waveOut device must never pin audio receiving
                    // forever after a lock/unlock or output-device change.
                    waveOutReset(device);
                    break;
                }
                Sleep(2);
            }
            if (!alive) break;
            if (slot.prepared) {
                waveOutUnprepareHeader(device, &slot.header, sizeof(WAVEHDR));
                slot.prepared = false;
            }
            slot.data = std::move(pcm);
            slot.header = WAVEHDR{};
            slot.header.lpData = slot.data.data();
            slot.header.dwBufferLength = static_cast<DWORD>(slot.data.size());
            if (waveOutPrepareHeader(device, &slot.header, sizeof(WAVEHDR)) == MMSYSERR_NOERROR) {
                slot.prepared = true;
                if (waveOutWrite(device, &slot.header, sizeof(WAVEHDR)) != MMSYSERR_NOERROR) {
                    // Let the next PCM chunk reopen WAVE_MAPPER.
                    waveOutReset(device);
                    for (Slot &slt : slots) {
                        if (slt.prepared) {
                            waveOutUnprepareHeader(device, &slt.header, sizeof(WAVEHDR));
                            slt.prepared = false;
                        }
                    }
                    waveOutClose(device);
                    device = nullptr;
                }
            }
        }
        if (device) {
            waveOutReset(device);
            for (Slot &slot : slots) {
                if (slot.prepared) waveOutUnprepareHeader(device, &slot.header, sizeof(WAVEHDR));
            }
            waveOutClose(device);
        }
        closesocket(s);
    }
    if (audioListener != INVALID_SOCKET) {
        closesocket(audioListener);
        audioListener = INVALID_SOCKET;
    }
}
