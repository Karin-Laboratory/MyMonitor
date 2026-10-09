# My Monitor

Android USB UVC camera viewer with screenshots, MP4 recording, local SAF folder export, SMB 2/3 upload, and live video transfer to Windows.

## Download 009

- [mymonitor009.apk](https://github.com/Karin-Laboratory/MyMonitor/releases/download/v0.09/mymonitor009.apk)
- [MyCastReceiver.exe](https://github.com/Karin-Laboratory/MyMonitor/releases/download/v0.09/MyCastReceiver.exe)
- [Instructions](TRANSFER.md)

The Windows receiver is a standalone x64 EXE. OBS uses Window Capture. Version 009 streams video only at 1920x1080, up to 15fps. 007 transfer was confirmed by the user. 008 Full HD transfer was confirmed by the user. 009 adds latest-frame replacement and receiver ACK flow control to prevent queued video latency; Wi-Fi latency still needs device measurement. Windows rendering is double buffered and uses the Android monitor icon. The release workflow smoke-tests the actual Windows EXE before publishing.

## Build

The `Build APK and Windows Receiver` GitHub Actions workflow builds and publishes both packages. Android requires Java 17 and Gradle 8.11.1; the Windows build uses MinGW-w64 C++17.

USB capture backend: [AndroidUSBCamera](https://github.com/ernestp/AndroidUSBCamera), Apache-2.0. SMB uses jcifs-ng. Android requires USB host hardware and camera/USB permission. SMB credentials are saved with the password encrypted through Android Keystore. Media is uploaded after local capture completes.
