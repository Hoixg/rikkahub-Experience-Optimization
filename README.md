<div align="center">
  <img src="docs/icon.png" alt="RikkaPlus icon" width="100" />
  <h1>RikkaPlus</h1>
  <p>A RikkaHub-based Android AI chat client, maintained by Hoixg.</p>

English | [简体中文](README_ZH_CN.md) | [繁體中文](README_ZH_TW.md)
</div>

RikkaPlus keeps syncing with [RikkaHub](https://github.com/rikkahub/rikkahub) while adding workflow improvements. Its development and release branch is `custom`.

## Download

[Download APKs from RikkaPlus Releases](https://github.com/Hoixg/rikkaplus/releases)

- Most Android phones: `app-arm64-v8a-release.apk`
- Universal package: `app-universal-release.apk`
- x86_64 devices: `app-x86_64-release.apk`

Older releases may still display the name **RikkaHub Custom**. The application ID remains `me.rerere.rikkahub.custom`, and the signing identity is retained for compatible upgrades. The app checks this repository for future releases.

## What this fork changes

- Improved sending queue handling and context indicators.
- Context compaction has no five-minute total deadline; the underlying network timeouts still apply.
- Image generation from chat through tools, with approval for each request.
- Simplified model deletion and ongoing upstream model compatibility updates.

## Core features

- Multiple AI providers and configurable models, API addresses, headers and request bodies.
- Multimodal messages, message branches and customizable assistants.
- Markdown, code, math and Mermaid rendering.
- Search, MCP tools, memory and a Linux workspace for agents.
- Material You design and dark mode.

## Feedback and source

- [Source code](https://github.com/Hoixg/rikkaplus/tree/custom)
- [Report a RikkaPlus issue](https://github.com/Hoixg/rikkaplus/issues)
- [Upstream usage documentation](https://docs.rikka-ai.com/introduction)

Please report issues with this fork to RikkaPlus. The linked RikkaHub documentation, community and donation pages belong to the upstream project.

## Development

Open the project in Android Studio. It uses Kotlin and Jetpack Compose.

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest :ai:testDebugUnitTest
```

Local builds require the appropriate Google Services and signing configuration. See `app/build.gradle.kts` for the configuration used by this fork.

## Upstream and license

RikkaPlus is based on [RikkaHub](https://github.com/rikkahub/rikkahub), created by [re-ovo](https://github.com/re-ovo). Thanks to the original author and contributors.

The project retains the [GNU AGPL v3 license](LICENSE). Original copyright notices remain in place.
