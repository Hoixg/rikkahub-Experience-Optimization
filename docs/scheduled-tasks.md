# 定时任务与后台权限

参考 [Yuihub fdae459](https://github.com/xiaoyuili/Yuihub/tree/fdae459a3da936abd99e70da437d5ce37924ad71)，移植至 RikkaPlus `custom` 分支。

## 使用入口

- 设置 → 定时任务：创建、编辑、删除、启停，查看下次触发时间、最近状态和结果会话。
- 助手详情 → 定时任务：管理该助手的任务。
- 设置 → 权限管理：查看通知、电池优化豁免与后台保活状态。
- 助手的 `scheduled_task` 工具支持 `list / create / update / delete / set_enabled`，仅能操作当前助手的任务，未提供的更新字段沿用原值。

每日任务默认本地时间 09:00；间隔任务默认 24 小时，最低 15 分钟；新建或改期的单次任务必须在未来。

## 执行与恢复

Room 31→32 新增独立 `scheduled_task` 表。原 `scheduled_jobs`、`scheduled_job_runs` 数据保留，继续清理旧排程，不恢复旧任务。

每个执行点对应独立的一次性 WorkManager 请求。执行标识及已消费的触发点保存在数据库中，防止重复启动和过期结果覆盖；同一任务执行中或等待审批时不会启动第二次执行。任务仅按排程执行，手动执行入口和工具操作已移除。启动时取消旧构建遗留的手动请求；Worker 也直接忽略此类请求。旧的数据库字段保留以兼容已安装构建的数据。

任务在所属助手下创建新会话，沿用其模型、预设和工具。助手已删除时记录失败。Worker 的前台执行覆盖实际生成；完成、失败、取消或等待审批先落库，再发送可打开会话的通知。等待审批时结束 Worker，用户从结果会话按现有聊天审批流程处理后继续生成并更新任务结果。

启动及备份恢复后校准排程，保留一个已延迟的执行点；未来每日任务按当前本地时区校准。系统时间、时区变化会更新受影响的排程。长时间自动执行结束后跳过已经错过的周期。进程中断的运行标记失败，不重复发送同一提示词；仍有持久化审批的会话可恢复审批。

## 权限与保活

`keepAwakeEnabled` 默认 `false`。用户开启开关且通知、电池豁免条件都满足时才启动保活；授予电池豁免不会打开开关。保活使用常驻通知、30 分钟超时的唤醒锁和 20 分钟提前续期，权限撤销、关闭或启动失败时停止并释放锁。

拒绝权限仍可保存任务。WorkManager 受 Android 后台与省电限制，可能延迟执行，保活也不保证准点。参见 [Android WorkManager 文档](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)。

## 验证记录（2026-10-02）

执行命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:lintDebug --continue --console=plain
```

- 移除手动执行后，JVM 单元测试全部通过，共 426 项，其中本次新增 23 项，覆盖时间计算、时区、DST、无效参数、助手隔离、字段保留、重复执行、停用状态和长时间执行。
- Android 测试代码编译通过。新增 Room/WorkManager 测试覆盖迁移、旧数据保留、取消、并发认领、审批恢复、进程恢复、恢复后排程校准；尚未在设备上执行。
- 宿主 SQLite 执行真实 31→32 迁移 SQL，通过旧数据保留及新表列、索引与 Room 32 schema 的一致性校验。这不替代设备上的 Room 迁移测试。
- Lint 未通过：项目现有 236 个错误（169 个缺失翻译、61 个 Compose 资源访问、3 个 Locale 状态、2 个本地属性转义、1 个 Context 转型）。新增功能文件无 Lint 报告项。

没有连接的 Android 设备或可用 AVD。Android 26、33、34、37（当前 targetSdk）上的后台生成、通知跳转、保活启停、权限撤销、省电延迟恢复，以及 `connectedDebugAndroidTest` 均未验证。

按最新要求仅交付源码；先前导出的安装包副本已清理。

接入设备后，运行 `:app:connectedDebugAndroidTest`，并在各版本实际检查：锁屏生成与结果通知跳转；多工具审批及重启后继续；关停保活与权限撤销后释放锁；Doze 后最多执行一次延迟任务；旧构建遗留的手动执行请求不再运行。
