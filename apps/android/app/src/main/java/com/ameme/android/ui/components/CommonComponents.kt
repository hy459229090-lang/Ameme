package com.ameme.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick as semanticsOnClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ameme.android.domain.ExperienceMode
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.MemoryEvent
import java.time.format.DateTimeFormatter

@Composable
fun StateNotice(
    mode: ExperienceMode,
    modifier: Modifier = Modifier,
    onAction: (() -> Unit)? = null,
) {
    if (mode == ExperienceMode.Ready || mode == ExperienceMode.Sparse || mode == ExperienceMode.Empty) return
    val icon: ImageVector = when (mode) {
        ExperienceMode.Loading -> Icons.Outlined.HourglassTop
        ExperienceMode.Partial -> Icons.Outlined.SyncProblem
        ExperienceMode.Offline -> Icons.Outlined.CloudOff
        ExperienceMode.RecoverableError -> Icons.Outlined.ErrorOutline
        ExperienceMode.PermissionLimited -> Icons.Outlined.Lock
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "${mode.label}：${mode.description}" }
            .then(
                if (onAction != null) {
                    Modifier.clickable(role = Role.Button, onClick = onAction)
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(mode.label, fontWeight = FontWeight.SemiBold)
                Text(mode.description, style = MaterialTheme.typography.bodySmall)
            }
            if (onAction != null) Text("查看", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun DemoModeNotice(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().semantics {
            contentDescription = "演示数据。固定示例仅用于体验，不会写入真实本机记录。"
        },
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("演示数据", fontWeight = FontWeight.SemiBold)
            Text(
                "固定示例仅用于体验，不会写入真实本机记录。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun EventRow(
    event: MemoryEvent,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp)
            .clearAndSetSemantics {
                contentDescription = buildString {
                    append(event.time?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "时间待确认")
                    append("，${event.title}，${event.factStatus.label}")
                    if (event.isLocalOnly) append("，仅本机")
                }
                role = Role.Button
                semanticsOnClick {
                    onClick()
                    true
                }
            },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            event.time?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "待定",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(event.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(event.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(event.factStatus)
                if (event.isLocalOnly) {
                    Text(
                        "仅本机",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun StatusChip(status: FactStatus) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    )
    {
        Text(
            status.label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
fun EmptyMessage(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
