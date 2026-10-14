# v026: full-screen gesture coordinates and mouse side buttons

- Fixed v025 touch Y scaling: Android AccessibilityService previously used resources.displayMetrics.heightPixels, which may exclude status/navigation bars. v026 uses WindowManager.defaultDisplay.getRealSize() to translate normalized 16-bit coordinates into physical display coordinates (Pixel 10a: 1080x2424).
- Keeps source gating: screen sharing only, AccessibilityService permission, Android per-session arming, Windows Alt+R toggle; UVC source cannot receive remote input.
- Windows WM_XBUTTONUP: mouse Back (XBUTTON1) => Android HOME (opcode 6); mouse Forward (XBUTTON2) => Android BACK (opcode 7).
- Ordinary right click => Android BACK (opcode 5) retained.
- MMC5 video/ACK/control packet remains identical width (17 bytes), preserves video/audio paths and v024 USB hotplug fix.
- Still requires testing on the user's hardware to verify coordinate correctness and side button behavior.
- Trust private LAN only: cryptographic pairing is not implemented.
