# MyMonitor v037 | UVC録画で赤い色成分が上にずれる問題の修正

GitHub Release: https://github.com/Karin-Laboratory/MyMonitor/releases/tag/v0.37

- `mymonitor037.apk`: Android版
- `mycastreceiver037.exe`: Windows x64受信機
- `SHA256SUMS.txt`: ファイルのハッシュ

## 修正内容

録画ファイル内で文字などの赤・青の色成分が上側へずれる症状に対して、上流AUSBCのH.264録画入力の処理を修正。UVCのNV12形式を正しく解釈し、Android MediaCodecが示すY・U・V各色成分の行幅（rowStride）・画素間隔（pixelStride）に従って録画します。

スマホの向きでUVCフレームを回転させない修正（v036）、Androidが認識したUSB音声の録音後MP4合成、Windows向けMJPEG低遅延転送、v035のUVCクラッシュ対策は維持。

**注意:** 実機のH.264エンコーダーによる録画をCIだけで検証することはできません。v037を導入したら、まず赤・青の字幕が含まれる10〜20秒の短いMP4を再生し、色のずれが解消したか確認してください。対応しないMediaCodecがある場合は、録画の開始に失敗することがあります。その場合はログを確認して別の入力経路を検討します。

Technical details: [SPEC_FIX_037.md](SPEC_FIX_037.md)
