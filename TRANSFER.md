# MyMonitor v038 | UVC録画で緑が消えて顔が青く見える問題

GitHub Release: https://github.com/Karin-Laboratory/MyMonitor/releases/tag/v0.38

- `mymonitor038.apk`: Android版
- `mycastreceiver038.exe`: Windows x64版
- `SHA256SUMS.txt`: ビルド成果物のチェックサム

## v038 変更点

v037で赤色輪郭の上下ずれを直したところ、録画映像の肌色が青くなり、緑色の再現が崩れた症状を修正します。原因は**実際のUVCネイティブ映像がNV21（V→U）なのに、Java側の誤ったコメントを信用してNV12（U→V）として扱っていたこと**。上流のC++変換コードを追跡して確認しました。

v038では、**v037の色成分の行間隔（stride）修正はそのまま**に、UとVの正しい割り当てだけを直します。USB接続、音声録画・AAC合成、Windows向け低遅延映像転送、遠隔操作は変更しません。

GitHub Actionsでは実際の上流C++コードに対する色順の照合と、赤色・strideの合成テストを追加しました。

注意：実機の色と音声の最終動作は自動テストでは検証できません。v038でテレビ映像を約10秒録画し、赤い字幕が正しい位置にあること、肌色・緑が戻っていること、音声が出ることを確認してください。

詳細: [SPEC_FIX_038.md](SPEC_FIX_038.md)
