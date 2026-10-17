# MyMonitor v036 | UVC録画の位置ずれ・USB音声付きMP4

Download after CI passes: https://github.com/Karin-Laboratory/MyMonitor/releases/tag/v0.36

- `mymonitor036.apk`: Android application, update from v035
- `mycastreceiver036.exe`: Windows x64 receiver, existing MyCast network protocol
- `SHA256SUMS.txt`: verified build checksums

## v036

USB-HDMIの録画は、スマホ本体の縦横に連動してUVCフレームを90度回転させていた上流AUSBC側の処理を修正。プレビューと同じHDMIの画素方向でMP4を保存します。

USB音声はv035からの安全なAndroidシステムの`AudioRecord`を引き続き使用し、USB入力の実ルートが確認できたときだけ記録します。録画終了後に別スレッドでAACにエンコードして、UVCの映像専用MP4と合成します。v034でクラッシュ原因候補だった`UACAudioHandler`によるネイティブUSB二重アクセスは使いません。

**注意:** 録画停止後のMP4音声追加には処理時間がかかります。音声が使えない場合は映像のみ保存。合成後も元の映像専用ファイルをバックアップとして残します。実機UVC/UACの動作確認は未実施です。

Technical notes: [SPEC_FIX_036.md](SPEC_FIX_036.md)
