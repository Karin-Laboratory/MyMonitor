# My Monitor

Android USB UVC camera viewer with screenshots, MP4 recording, local SAF folder export, SMB 2/3 upload, and live video transfer to Windows.

## Download 008

- [mymonitor008.apk](https://github.com/Karin-Laboratory/MyMonitor/releases/download/v0.08/mymonitor008.apk)
- [MyCastReceiver.exe](https://github.com/Karin-Laboratory/MyMonitor/releases/download/v0.08/MyCastReceiver.exe)
- [Instructions](TRANSFER.md)

The Windows receiver is a standalone x64 EXE. OBS uses Window Capture. Version 008 streams video only at 1920x1080, up to 10fps. 007 transfer was confirmed by the user. 008 Full HD and flicker correction await device verification. Windows rendering is double buffered.

## Build

The `Build APK and Windows Receiver` GitHub Actions workflow builds and publishes both packages. Android requires Java 17 and Gradle 8.11.1; the Windows build uses MinGW-w64 C++17.

USB capture backend: [AndroidUSBCamera](https://github.com/ernestp/AndroidUSBCamera), Apache-2.0. SMB uses jcifs-ng. Android requires USB host hardware and camera/USB permission. SMB credentials are saved with the password encrypted through Android Keystore. Media is uploaded after local capture completes.
