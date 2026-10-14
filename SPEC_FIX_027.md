# MyMonitor v027 release notes

v027 preserves v026 remote-control behavior and adds fixes isolated by the safety audit.

- Screen mirroring geometry is recalculated when the phone physical display changes (Home to landscape apps). Stale captured frames are skipped.
- Android WindowsSender connection notifications now reflect successful video acknowledgements.
- Windows Alt+R ignores repeated key-down events and clears pending remote commands when disabled.
- Windows releases mouse capture after button-up even when touch input was recently detected.
- Contract checks verify display-listener registration, cleanup and frame watchdog.

UVC reconnect implementation, MJPEG/PCM protocols, physical screen gesture coordinates, mouse side buttons and SMB export remain unchanged. Hardware-only behavior still requires testing. Windows EXE is unsigned and remote control is restricted to trusted private LAN use.
