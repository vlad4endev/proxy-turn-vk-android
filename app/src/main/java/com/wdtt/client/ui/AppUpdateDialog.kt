package com.wdtt.client.ui

import com.wdtt.client.ui.theme.AdaptiveLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wdtt.client.AppReleaseInfo
import com.wdtt.client.BuildConfig

@Composable
fun AppUpdateDialog(
    release: AppReleaseInfo,
    onPostpone: () -> Unit,
    onUpdate: () -> Unit
) {
    val context = LocalContext.current
    var downloading by remember { mutableStateOf(false) }
    var changelogExpanded by remember { mutableStateOf(true) }

    val changelogItems = remember(release.releaseNotes) {
        parseChangelog(release.releaseNotes)
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = AdaptiveLayout.MaxDialogWidth)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "Доступно обновление",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = BuildConfig.VERSION_NAME,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "→",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = release.versionTag.removePrefix("v"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Changelog section
                if (changelogItems.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Collapse/expand header
                            TextButton(
                                onClick = { changelogExpanded = !changelogExpanded },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp)
                            ) {
                                Text(
                                    text = "Что нового",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = if (changelogExpanded)
                                        Icons.Default.KeyboardArrowUp
                                    else
                                        Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            AnimatedVisibility(
                                visible = changelogExpanded,
                                enter = expandVertically(),
                                exit = shrinkVertically()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    changelogItems.forEach { item ->
                                        ChangelogItem(item)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                } else {
                    // No notes — short stub
                    Text(
                        text = "Исправления и улучшения стабильности.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(14.dp))
                }

                if (downloading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Загрузка... APK появится в уведомлениях",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                Spacer(Modifier.height(14.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onPostpone,
                        enabled = !downloading,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Text("Позже", fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = {
                            downloading = true
                            val apkUrl = "https://github.com/vlad4endev/skyflow-vpn-android/releases/latest/download/skyflow-m.apk"
                            val request = android.app.DownloadManager.Request(
                                android.net.Uri.parse(apkUrl)
                            ).apply {
                                setTitle("SKYFLOW M ${release.versionTag} — обновление")
                                setDescription("Загрузка новой версии...")
                                setNotificationVisibility(
                                    android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                                )
                                setDestinationInExternalPublicDir(
                                    android.os.Environment.DIRECTORY_DOWNLOADS,
                                    "skyflow-m-update.apk"
                                )
                                setMimeType("application/vnd.android.package-archive")
                            }
                            val dm = context.getSystemService(android.app.DownloadManager::class.java)
                            dm.enqueue(request)
                            onUpdate()
                        },
                        enabled = !downloading,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Text("Обновить", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangelogItem(item: ChangelogEntry) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = item.emoji,
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 1.dp)
        )
        Text(
            text = item.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 20.sp
        )
    }
}

private data class ChangelogEntry(val emoji: String, val text: String)

private fun parseChangelog(notes: String?): List<ChangelogEntry> {
    if (notes.isNullOrBlank()) return emptyList()

    return notes.lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            // Strip markdown list markers: "- ", "* ", "• "
            val text = line.removePrefix("- ").removePrefix("* ").removePrefix("• ").trim()
            if (text.isBlank()) return@mapNotNull null

            // Classify by keywords → assign emoji
            val emoji = when {
                text.contains(Regex("(?i)исправ|фикс|fix|баг|bug|crash|краш|ошибк")) -> "🔧"
                text.contains(Regex("(?i)добавл|новый|новая|новое|new|feat|функц")) -> "✨"
                text.contains(Regex("(?i)скорост|быстр|производ|перформ|speed|perf|оптимиз")) -> "⚡"
                text.contains(Regex("(?i)безопас|security|защит|шифр")) -> "🔒"
                text.contains(Regex("(?i)интерфейс|ui|дизайн|вид|экран|кнопк|иконк")) -> "🎨"
                text.contains(Regex("(?i)vpn|туннел|connect|подключ|соедин|сеть")) -> "🌐"
                text.contains(Regex("(?i)капча|captcha")) -> "🤖"
                text.contains(Regex("(?i)уведомл|notif|пуш|push")) -> "🔔"
                text.contains(Regex("(?i)обновл|update|версия|version")) -> "📦"
                else -> "•"
            }
            ChangelogEntry(emoji, text)
        }
}
