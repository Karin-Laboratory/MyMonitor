# My Monitor

Android USB UVC camera viewer, screenshot capture, MP4 recording, local SAF folder export and SMB 2/3 upload.

**Status:** Source development build; unverified on physical UVC devices.

Build using the GitHub Actions `Build APK` workflow; download its `mymonitor` artifact once successful.

USB capture backend: [AndroidUSBCamera](https://github.com/ernestp/AndroidUSBCamera), Apache-2.0. SMB implementation uses jcifs-ng.

Requires Android USB host hardware (OTG). The USB permission prompt must be accepted on initial connection. SMB files are uploaded after local recording completes; SMB credentials are held in memory for the running session only.
