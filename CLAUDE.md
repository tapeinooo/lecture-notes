# たぺいノート（lecture-notes）

集会・講演・大会・会話を録音しながら文字起こしし、無料の AI で整形・要約する自分用の Android アプリ。共通ルールの見本（build.yml・Updater.kt はここのものがもと）。

## 共通ルール

このアプリは自分用アプリの共通ルールに従う：
https://github.com/tapeinooo/app-rules/blob/main/RULES.md

作業を始める前に読むこと（読めないときは `add_repo` で `tapeinooo/app-rules` を足すか、
https://raw.githubusercontent.com/tapeinooo/app-rules/main/RULES.md を開く）。
主な点：GitHub Actions でビルド・main への push で自動リリース・版の番号は自動・同じ署名の鍵・アプリの中の更新・
コミットの作者は tapeinooo の noreply・秘密はリポジトリに入れない・利用者への説明は日本語でやさしく。

## このアプリだけの決まり

- 機能の説明は README.md。利用者（自分）とのやりとりは日本語で、やさしく。
- Google ドライブの控えのフォルダ名は「たっぴぃず録音」のまま（動いている Apps Script がこの名前を使うため）。
- jw.org・wol.jw.org の中身をアプリから取りに行かない（利用規約）。リンクを開くだけ。
