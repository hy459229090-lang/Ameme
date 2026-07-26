package com.ameme.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick as semanticsOnClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .semantics {
                contentDescription = "演示数据。固定示例仅用于体验，不会写入真实本机记录。"
            },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.PlayCircleOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            "演示数据",
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            "· 不写入本机",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun EventRow(
    event: MemoryEvent,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val useStackedLayout = LocalDensity.current.fontScale >= 1.5f
    val rowModifier = modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(vertical = 16.dp, horizontal = 4.dp)
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
        }

    if (useStackedLayout) {
        Column(
            modifier = rowModifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EventTime(event)
                EventStatus(event)
            }
            Text(
                event.title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                event.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Row(
            modifier = rowModifier,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            EventTime(event, modifier = Modifier.width(64.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        event.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    EventStatus(event)
                }
                Text(
                    event.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = if (useStackedLayout) 0.dp else 80.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
    )
}

@Composable
private fun EventTime(
    event: MemoryEvent,
    modifier: Modifier = Modifier,
) {
    Text(
        event.time?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "待定",
        modifier = modifier,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun EventStatus(event: MemoryEvent) {
    Text(
        buildString {
            append(event.factStatus.compactLabel)
            if (event.isLocalOnly) append(" · 仅本机")
        },
        style = MaterialTheme.typography.labelMedium,
        color = statusColor(event.factStatus),
    )
}

private val FactStatus.compactLabel: String
    get() = when (this) {
        FactStatus.Confirmed, FactStatus.UserAsserted -> "已记录"
        FactStatus.Planned -> "计划"
        FactStatus.Inferred -> "推测"
        FactStatus.NeedsReview -> "待核验"
        FactStatus.Conflict -> "冲突"
        FactStatus.Processing -> "整理中"
    }

@Composable
private fun statusColor(status: FactStatus): Color {
    return when (status) {
        FactStatus.Confirmed, FactStatus.UserAsserted -> MaterialTheme.colorScheme.primary
        FactStatus.NeedsReview, FactStatus.Conflict -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
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
