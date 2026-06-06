package com.wdtt.client.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.compose.runtime.Stable

@Stable
data class AppItem(
    val name: String,
    val packageName: String,
    val icon: ImageBitmap?,
    val isSystem: Boolean
)

object AppCache {
    var cachedList: List<AppItem>? = null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExceptionsTab() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore(context) }

    val savedExcluded by settingsStore.excludedApps.collectAsStateWithLifecycle(initialValue = "")
    val selectedPackages = remember(savedExcluded) {
        savedExcluded.split(",").filter { it.isNotEmpty() }.toSet()
    }

    var appsList by remember { mutableStateOf<List<AppItem>>(AppCache.cachedList ?: emptyList()) }
    var isLoading by remember { mutableStateOf(AppCache.cachedList == null) }
    var searchQuery by remember { mutableStateOf("") }

    val showSystemAppsOpt by settingsStore.showSystemApps.collectAsStateWithLifecycle(initialValue = null)

    val isWhitelist by settingsStore.isWhitelist.collectAsStateWithLifecycle(initialValue = false)

    // Load Apps
    LaunchedEffect(Unit) {
        if (AppCache.cachedList != null) return@LaunchedEffect
        isLoading = true
        withContext(Dispatchers.IO) {
            val list = mutableListOf<AppItem>()
            val pm = context.packageManager
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

            installedApps.forEach { app ->
                if (app.packageName != context.packageName &&
                    !app.packageName.contains("vkontakte") &&
                    !app.packageName.contains("vk.calls")) {
                    val isSys = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    list.add(AppItem(
                        name = app.loadLabel(pm).toString(),
                        packageName = app.packageName,
                        icon = app.loadIcon(pm)?.toBitmap()?.asImageBitmap(),
                        isSystem = isSys
                    ))
                }
            }
            appsList = list.sortedBy { it.name.lowercase() }
            AppCache.cachedList = appsList
        }
        isLoading = false
    }

    val filteredApps by remember {
        derivedStateOf {
            val showSystemApps = showSystemAppsOpt ?: true
            val list = if (showSystemApps) {
                appsList
            } else {
                appsList.filter {
                    !it.isSystem || it.packageName == "com.google.android.youtube" || it.packageName == "com.android.vending"
                }
            }

            if (searchQuery.isBlank()) list
            else list.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val excludedCount = selectedPackages.size

    Column(modifier = Modifier.fillMaxSize()) {

        Spacer(Modifier.height(8.dp))

        // Mode block
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF13112A),
            border = BorderStroke(0.5.dp, Color(0xFF1E1C3A))
        ) {
            Column(Modifier.padding(10.dp)) {

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Режим исключений",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFD4D4D8)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        val isBlacklistActive = !isWhitelist
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isBlacklistActive) Color(0xFF6366F1) else Color(0xFF1E1C3A),
                            modifier = Modifier.clickable {
                                if (isWhitelist) {
                                    scope.launch {
                                        val all = appsList.map { it.packageName }.toSet()
                                        val inverted = all - selectedPackages
                                        settingsStore.saveExceptionsMode(inverted.joinToString(","), false)
                                        delay(300)
                                        com.wdtt.client.TunnelManager.reloadWireGuard()
                                    }
                                }
                            }
                        ) {
                            Text(
                                "Чёрный список",
                                fontSize = 7.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isBlacklistActive) Color.White else Color(0xFF6B7280),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }

                        val isWhitelistActive = isWhitelist
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isWhitelistActive) Color(0xFF6366F1) else Color(0xFF1E1C3A),
                            modifier = Modifier.clickable {
                                if (!isWhitelist) {
                                    scope.launch {
                                        val all = appsList.map { it.packageName }.toSet()
                                        val inverted = all - selectedPackages
                                        settingsStore.saveExceptionsMode(inverted.joinToString(","), true)
                                        delay(300)
                                        com.wdtt.client.TunnelManager.reloadWireGuard()
                                    }
                                }
                            }
                        ) {
                            Text(
                                "Белый список",
                                fontSize = 7.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isWhitelistActive) Color.White else Color(0xFF6B7280),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (!isWhitelist) "Выбранные приложения не используют VPN-туннель"
                           else "Только выбранные приложения используют VPN-туннель",
                    fontSize = 7.5.sp,
                    color = Color(0xFF6B7280),
                    lineHeight = 11.sp
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = Color(0xFF1E1C3A),
                    thickness = 0.5.dp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Системные приложения",
                        fontSize = 9.sp,
                        color = Color(0xFFD4D4D8)
                    )
                    Switch(
                        checked = showSystemAppsOpt ?: true,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                settingsStore.saveShowSystemApps(enabled)
                            }
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Search
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF13112A),
            border = BorderStroke(0.5.dp, Color(0xFF1E1C3A))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = Color(0xFF6B7280),
                    modifier = Modifier.size(11.dp)
                )
                Box(Modifier.fillMaxWidth()) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            "Поиск приложений...",
                            fontSize = 9.sp,
                            color = Color(0xFF6B7280)
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        textStyle = TextStyle(fontSize = 9.sp, color = Color(0xFFD4D4D8)),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // App list header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "ПРИЛОЖЕНИЯ",
                fontSize = 8.sp,
                color = Color(0xFF6B7280),
                letterSpacing = 0.6.sp
            )
            Text(
                "$excludedCount исключено",
                fontSize = 8.sp,
                color = Color(0xFF6366F1),
                fontWeight = FontWeight.SemiBold
            )
        }

        // App list
        if (isLoading || showSystemAppsOpt == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    val isSelected = selectedPackages.contains(app.packageName)

                    AppRow(
                        app = app,
                        isSelected = isSelected,
                        onClick = {
                            val newList = if (isSelected) {
                                selectedPackages - app.packageName
                            } else {
                                selectedPackages + app.packageName
                            }
                            scope.launch {
                                settingsStore.saveExcludedApps(newList.joinToString(","))
                                com.wdtt.client.TunnelManager.reloadWireGuard()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun AppRow(app: AppItem, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF13112A),
        border = BorderStroke(0.5.dp, Color(0xFF1E1C3A))
    ) {
        Row(
            modifier = Modifier
                .clickable { onClick() }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (app.icon != null) {
                Image(
                    bitmap = app.icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(Color(0xFF2D2B52), RoundedCornerShape(6.dp))
                )
            }

            Column(Modifier.weight(1f)) {
                Text(
                    text = app.name,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFD4D4D8),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    fontSize = 6.5.sp,
                    color = Color(0xFF6B7280),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isSelected) Color(0xFF6366F1) else Color.Transparent)
                    .border(
                        width = 1.5.dp,
                        color = if (isSelected) Color(0xFF6366F1) else Color(0xFF2D2B52),
                        shape = RoundedCornerShape(4.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(9.dp)
                    )
                }
            }
        }
    }
}
