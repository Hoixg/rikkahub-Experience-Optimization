<div align="center">
  <img src="docs/icon.png" alt="RikkaPlus 圖示" width="100" />
  <h1>RikkaPlus</h1>
  <p>基於 RikkaHub 的 Android AI 聊天用戶端，由 Hoixg 維護。</p>

[English](README.md) | [简体中文](README_ZH_CN.md) | 繁體中文
</div>

RikkaPlus 持續同步 [RikkaHub](https://github.com/rikkahub/rikkahub)，同時改善聊天與工具使用體驗。開發與發佈分支為 `custom`。

## 下載

[從 RikkaPlus Releases 下載 APK](https://github.com/Hoixg/rikkaplus/releases)

- 大多數 Android 手機：`app-arm64-v8a-release.apk`
- 通用安裝包：`app-universal-release.apk`
- x86_64 裝置：`app-x86_64-release.apk`

歷史版本可能仍顯示 **RikkaHub Custom**。套件名稱維持 `me.rerere.rikkahub.custom`，沿用現有簽章以支援覆蓋升級。App 的後續更新從本儲存庫取得。

## 本分支的改動

- 改善傳送佇列處理與上下文用量指示器。
- 取消上下文壓縮的 5 分鐘總時限，底層網路逾時仍然生效。
- 支援透過聊天工具產生圖片，每次請求需要使用者授權。
- 簡化模型刪除，並持續同步上游的模型相容性更新。

## 基礎功能

- 多種 AI 供應商，自訂模型、API 位址、請求標頭與請求內容。
- 多模態訊息、訊息分支及自訂助理。
- Markdown、程式碼、數學公式和 Mermaid 呈現。
- 搜尋、MCP 工具、記憶與供智慧代理使用的 Linux 工作區。
- Material You 介面與深色模式。

## 意見回饋與原始碼

- [原始碼](https://github.com/Hoixg/rikkaplus/tree/custom)
- [回報 RikkaPlus 的問題](https://github.com/Hoixg/rikkaplus/issues)
- [上游使用文件](https://docs.rikka-ai.com/zh/introduction)

本分支的問題請回報到 RikkaPlus。連結中的 RikkaHub 文件、社群及捐贈頁面屬於上游專案。

## 開發

使用 Android Studio 開啟專案。主要技術為 Kotlin 與 Jetpack Compose。

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest :ai:testDebugUnitTest
```

本機建置需要相應的 Google Services 與簽章設定，具體設定見 `app/build.gradle.kts`。

## 上游與授權條款

RikkaPlus 基於 [re-ovo](https://github.com/re-ovo) 建立的 [RikkaHub](https://github.com/rikkahub/rikkahub)。感謝原作者與貢獻者。

專案保留 [GNU AGPL v3 授權條款](LICENSE) 與原有版權聲明。
