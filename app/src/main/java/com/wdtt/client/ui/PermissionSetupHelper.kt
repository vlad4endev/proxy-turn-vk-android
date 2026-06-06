package com.wdtt.client.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

enum class SetupPermissionStep {
    NOTIFICATIONS,
    BATTERY,
    VPN,
}

data class PermissionStepContent(
    val emoji: String,
    val dialogTitle: String,
    val dialogBody: String,
    val allowLabel: String,
    val hint: String,
    val pageTitle: String,
    val pageBody: String,
)

sealed class PermissionRequest {
    data class Runtime(val permission: String) : PermissionRequest()
    data class Settings(val intent: Intent) : PermissionRequest()
}

object PermissionSetupHelper {

    fun buildSteps(context: Context): List<SetupPermissionStep> {
        val steps = mutableListOf<SetupPermissionStep>()
        if (needsNotificationSetup(context)) {
            steps.add(SetupPermissionStep.NOTIFICATIONS)
        }
        if (needsBatterySetup(context)) {
            steps.add(SetupPermissionStep.BATTERY)
        }
        if (needsVpnSetup(context)) {
            steps.add(SetupPermissionStep.VPN)
        }
        return steps
    }

    fun needsNotificationSetup(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        } else {
            !NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    fun needsBatterySetup(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            !pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) {
            false
        }
    }

    fun needsVpnSetup(context: Context): Boolean {
        return try {
            VpnService.prepare(context) != null
        } catch (_: Exception) {
            false
        }
    }

    fun createRequest(step: SetupPermissionStep, context: Context): PermissionRequest? {
        return when (step) {
            SetupPermissionStep.NOTIFICATIONS -> createNotificationRequest(context)
            SetupPermissionStep.BATTERY -> PermissionRequest.Settings(createBatteryRequestIntent(context))
            SetupPermissionStep.VPN -> createVpnRequest(context)?.let(PermissionRequest::Settings)
        }
    }

    private fun createNotificationRequest(context: Context): PermissionRequest? {
        if (!needsNotificationSetup(context)) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionRequest.Runtime(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            PermissionRequest.Settings(createNotificationSettingsIntent(context))
        }
    }

    private fun createVpnRequest(context: Context): Intent? {
        return try {
            VpnService.prepare(context)
        } catch (_: Exception) {
            null
        }
    }

    fun createNotificationSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        } else {
            createAppDetailsIntent(context)
        }
    }

    fun createBatteryRequestIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            if (canResolveActivity(context, direct)) {
                return direct
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            if (canResolveActivity(context, list)) {
                return list
            }
        }
        return createAppDetailsIntent(context)
    }

    private fun canResolveActivity(context: Context, intent: Intent): Boolean {
        return context.packageManager.resolveActivity(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY
        ) != null
    }

    fun createAppDetailsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    fun stepContent(step: SetupPermissionStep): PermissionStepContent {
        val usesRuntimeNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        return when (step) {
            SetupPermissionStep.NOTIFICATIONS -> PermissionStepContent(
                emoji = "🔔",
                dialogTitle = "SKYFLOW M хочет\nотправлять уведомления",
                dialogBody = "Чтобы видеть статус\nподключения в фоне",
                allowLabel = "✓  Разрешить",
                hint = "⚠  Нажми «Разрешить» — так ты\nувидишь статус защиты",
                pageTitle = "Разреши уведомления",
                pageBody = if (usesRuntimeNotifications) {
                    "Android запросит разрешение один раз.\nЭто нужно для статуса подключения."
                } else {
                    "Откроются настройки уведомлений.\nВключи их для статуса подключения."
                },
            )
            SetupPermissionStep.BATTERY -> PermissionStepContent(
                emoji = "🔋",
                dialogTitle = "SKYFLOW M просит\nотключить оптимизацию",
                dialogBody = "Чтобы соединение не\nобрывалось в фоне",
                allowLabel = "✓  Разрешить",
                hint = "⚠  Нажми «Разрешить» — так защита\nне будет отключаться сама",
                pageTitle = "Отключи оптимизацию\nбатареи",
                pageBody = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    "Android откроет системный запрос или настройки.\nВыбери «Разрешить» для стабильной работы."
                } else {
                    "Откроются настройки приложения.\nОтключи ограничения для стабильной работы."
                },
            )
            SetupPermissionStep.VPN -> PermissionStepContent(
                emoji = "🔒",
                dialogTitle = "SKYFLOW M хочет\nсоздать VPN",
                dialogBody = "Приложение будет\nперехватывать трафик",
                allowLabel = "✓  Разрешить",
                hint = "⚠  Нажми «Разрешить» — это нужно\nдля работы защиты",
                pageTitle = "Разреши создание\nзащищённого соединения",
                pageBody = "Android запросит разрешение один раз.\nЭто стандартная процедура — нажми «ОК».",
            )
        }
    }
}
