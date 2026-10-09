# My Monitor

Android USB UVC camera viewer with screenshots, MP4 recording, local SAF folder export, SMB 2/3 upload, and live video transfer to Windows.

## Download 007

- [mymonitor007.apk](https://github.com/Karin-Laboratory/MyMonitor/releases/download/v0.07/mymonitor007.apk)
- [MyCastReceiver.exe](https://github.com/Karin-Laboratory/MyMonitor/releases/download/v0.07/MyCastReceiver.exe)
- [Instructions](TRANSFER.md)

The Windows receiver is a standalone x64 EXE. OBS uses Window Capture. Version 007 streams video only at 640x480, up to 10fps. End-to-end testing on physical Pixel/UVC/Windows hardware is pending.

## Build

The `Build APK and Windows Receiver` GitHub Actions workflow builds and publishes both packages. Android requires Java 17 and Gradle 8.11.1; the Windows build uses MinGW-w64 C++17.

USB capture backend: [AndroidUSBCamera](https://github.com/ernestp/AndroidUSBCamera), Apache-2.0. SMB uses jcifs-ng. Android requires USB host hardware and camera/USB permission. SMB credentials are saved with the password encrypted through Android Keystore. Media is uploaded after local capture completes.
