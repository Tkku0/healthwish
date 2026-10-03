package com.healthwish.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthwish.app.model.DayLog
import com.healthwish.app.model.HabitTask

val CardBg = Color(0xFF16161E)
val CardBorder = Color(0xFF262636)
val AccentPink = Color(0xFFFF6584)
val AccentPurple = Color(0xFF9D65C9)
val AccentGreen = Color(0xFF5DD39E)
val TextMuted = Color(0xFF8E8EA0)

@Composable
fun SectionHeader(title: String, subtitle: String? = null, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp))
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(color = TextMuted))
            }
        }
        action?.invoke()
    }
}

@Composable
fun ModernCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content
        )
    }
}

@Composable
fun MealItem(
    emoji: String,
    title: String,
    value: String,
    isEditable: Boolean,
    onEdit: () -> Unit
) {
    val hasContent = value.isNotBlank()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (hasContent) Color(0xFF1E1E2C) else Color(0xFF14141C))
            .clickable(enabled = isEditable, onClick = onEdit)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF262638)),
                contentAlignment = Alignment.Center
            ) {
                Text(emoji, fontSize = 20.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(
                    if (hasContent) value else "Tap to add...",
                    color = if (hasContent) Color.White.copy(alpha = 0.9f) else TextMuted,
                    fontSize = 13.sp,
                    maxLines = 2
                )
            }
        }
        if (isEditable) {
            Icon(
                if (hasContent) Icons.Rounded.Edit else Icons.Rounded.AddCircle,
                contentDescription = null,
                tint = if (hasContent) AccentPink else TextMuted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun WaterTrackerRow(
    glasses: Int,
    isEditable: Boolean,
    onUpdate: (Int) -> Unit
) {
    ModernCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("💧", fontSize = 26.sp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Water Intake", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("$glasses / 8 glasses target", color = TextMuted, fontSize = 13.sp)
                }
            }
            if (isEditable) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledIconButton(
                        onClick = { if (glasses > 0) onUpdate(glasses - 1) },
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF262638))
                    ) {
                        Icon(Icons.Default.Remove, null, tint = Color.White)
                    }
                    Text(
                        "$glasses",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color(0xFF64B5F6),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    FilledIconButton(
                        onClick = { onUpdate(glasses + 1) },
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = AccentPink)
                    ) {
                        Icon(Icons.Default.Add, null, tint = Color.White)
                    }
                }
            } else {
                Text("$glasses 🥛", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF64B5F6))
            }
        }
    }
}
