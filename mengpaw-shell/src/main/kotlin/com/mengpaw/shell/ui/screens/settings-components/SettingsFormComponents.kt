// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing

@Composable
fun SettingsSwitch(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String, subtitle: String, checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = ArcoSpacing.sm)) {
        Icon(icon, contentDescription = null, tint = ArcoColors.Gray6, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = ArcoColors.Blue6))
    }
}

@Composable
fun SettingsTextField(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String, value: String,
    onValueChange: (String) -> Unit,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = ArcoColors.Gray6, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(ArcoSpacing.md))
        OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text(label) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            shape = RoundedCornerShape(ArcoRadius.md),
            visualTransformation = visualTransformation, trailingIcon = trailingIcon,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ArcoColors.Blue6,
                unfocusedBorderColor = ThemeColors.border,
                focusedLabelColor = ArcoColors.Blue6))
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = ArcoSpacing.xs), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ThemeColors.textSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun NavigationLink(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(ArcoRadius.lg),
        color = ThemeColors.bgCard, modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(ArcoSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = ThemeColors.brand, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(ArcoSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = ThemeColors.brand)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = ThemeColors.textSecondary)
        }
    }
}
