<div align="center">
  <img src="docs/icon.png" alt="RikkaPlus 图标" width="100" />
  <h1>RikkaPlus</h1>
  <p>基于 RikkaHub 的 Android AI 聊天客户端，由 Hoixg 维护。</p>

[English](README.md) | 简体中文 | [繁體中文](README_ZH_TW.md)
</div>

RikkaPlus 持续同步 [RikkaHub](https://github.com/rikkahub/rikkahub)，同时改进聊天和工具使用体验。开发与发布分支为 `custom`。

## 下载

[从 RikkaPlus Releases 下载 APK](https://github.com/Hoixg/rikkaplus/releases)

- 大多数 Android 手机：`app-arm64-v8a-release.apk`
- 通用安装包：`app-universal-release.apk`
- x86_64 设备：`app-x86_64-release.apk`

历史版本可能仍显示 **RikkaHub Custom**。包名保持 `me.rerere.rikkahub.custom`，沿用现有签名以支持覆盖升级。App 的后续更新从本仓库获取。

## 本分支的改动

- 改进发送队列处理和上下文用量指示器。
- 取消上下文压缩的 5 分钟总时限，底层网络超时仍然生效。
- 支持通过聊天工具生成图片，每次请求需要用户授权。
- 简化模型删除，并持续同步上游的模型兼容性更新。

## 基础功能

- 多种 AI 供应商，自定义模型、API 地址、请求头和请求体。
- 多模态消息、消息分支及自定义助手。
- Markdown、代码、数学公式和 Mermaid 渲染。
- 搜索、MCP 工具、记忆和供智能体使用的 Linux 工作区。
- Material You 界面及深色模式。

## 反馈与源码

- [源码](https://github.com/Hoixg/rikkaplus/tree/custom)
- [反馈 RikkaPlus 的问题](https://github.com/Hoixg/rikkaplus/issues)
- [上游使用文档](https://docs.rikka-ai.com/zh/introduction)

本分支的问题请反馈到 RikkaPlus。链接中的 RikkaHub 文档、社群和捐赠页面属于上游项目。

## 开发

使用 Android Studio 打开项目。主要技术为 Kotlin 和 Jetpack Compose。

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest :ai:testDebugUnitTest
```

本地构建需要相应的 Google Services 和签名配置，具体配置见 `app/build.gradle.kts`。

## 上游与许可证

RikkaPlus 基于 [re-ovo](https://github.com/re-ovo) 创建的 [RikkaHub](https://github.com/rikkahub/rikkahub)。感谢原作者及贡献者。

项目保留 [GNU AGPL v3 许可证](LICENSE) 和原有版权声明。
