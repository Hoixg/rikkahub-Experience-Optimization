package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.runtime.Composable

/** Retain the old preferences route while using the single background settings page. */
@Composable
fun SettingPreferencesNotificationPage(vm: SettingVM = org.koin.androidx.compose.koinViewModel()) {
    SettingPermissionsPage()
}
