package me.rerere.rikkahub.di

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.preferences.TermuxPreferences
import me.rerere.rikkahub.data.ai.tools.local.SafPickerResultBuffer
import me.rerere.rikkahub.data.storage.StorageVolumeGrantStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.service.ChatNotificationManager
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.scheduled.ScheduledTaskManager
import me.rerere.rikkahub.service.scheduled.ScheduledTaskWorker
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalSessionManager
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.SoundEffectPlayer
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.tts.provider.TTSManager
import org.koin.dsl.module
import org.koin.androidx.workmanager.dsl.worker

val appModule = module {
    worker { params -> ScheduledTaskWorker(params.get(), params.get()) }

    single<Json> { JsonInstant }

    single {
        AppEventBus()
    }

    single { TermuxPreferences(get()) }
    single {
        LocalTools(get(), get(), get(), get(), get(), get(), get())
    }
    single { StorageVolumeGrantStore(get()) }
    single { SafPickerResultBuffer() }

    single {
        UpdateChecker(
            client = get(),
            appScope = get(),
        )
    }

    single {
        AppScope()
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
    }

    single {
        SoundEffectPlayer(get())
    }

    single {
        WorkspaceTerminalSessionManager(get(), get(), get())
    }

    // 生成通知与业务解耦：ChatService 只发事件，通知由这里消费；
    // createdAtStart 保证进程启动即订阅，否则后台生成的事件会因无订阅者而丢失
    single(createdAtStart = true) {
        ChatNotificationManager(
            context = get(),
            appScope = get(),
            eventBus = get(),
            settingsStore = get(),
            scheduledTaskDao = get(),
        )
    }

    single {
        ChatToolFactory(
            json = get(),
            memoryRepository = get(),
            conversationRepository = get(),
            localTools = get(),
            mcpManager = get(),
            settingsStore = get(),
            skillManager = get(),
            workspaceRepository = get(),
        )
    }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            appEventBus = get(),
            settingsStore = get(),
            conversationRepo = get(),
            memoryRepository = get(),
            generationLoop = get(),
            translationHandler = get(),
            templateTransformer = get(),
            providerManager = get(),
            chatToolFactory = get(),
            mcpManager = get(),
            filesManager = get(),
            workspaceRepository = get(),
            folderRepository = get(),
            scheduledTaskDao = get(),
        )
    }

    single {
        ScheduledTaskManager(
            context = get(),
            appScope = get(),
            dao = get(),
            chatService = get(),
            eventBus = get(),
        )
    }
}
