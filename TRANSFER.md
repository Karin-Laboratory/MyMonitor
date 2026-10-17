# MyMonitor v039 | 録画だけ非圧縮YUY2＋高画質H.264

正式配布: https://github.com/Karin-Laboratory/MyMonitor/releases/tag/v0.39

- `mymonitor039.apk`: Android APK
- `mycastreceiver039.exe`: Windows x64受信EXE（従来MJPEG低遅延維持）
- `SHA256SUMS.txt`: ファイル検証用チェックサム

設定「高画質録画（対応時は非圧縮YUY2を使用）」はデフォルトON。

録画時だけ、現在の解像度で機器が非圧縮YUY2に対応しているか判定し、対応時はUVCを再オープンして高ビットレートH.264でMP4保存。終了後はMJPEGに自動復帰。YUY2の帯域不足や接続失敗時はMJPEG録画にフォールバックします。録画中のWindowsへのMJPEG直接転送は一時停止します。**録画の高画質化を優先した機能で、Windows転送は低遅延を維持します。**

実機のfpsや画質改善にはUSB 3.x接続等の影響があります。UVC/USB音声を二重に開かない構成、音声付きMP4合成、色成分の修正はv038を維持。実機テスト前に成功を断言しないでください。

詳細 [SPEC_FIX_039.md](SPEC_FIX_039.md)
