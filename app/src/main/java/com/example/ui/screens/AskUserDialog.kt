package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.mcp.AskUserManager.AskUserRequest
import com.example.ui.theme.LocalUISettings
import com.example.ui.theme.resolveFontFamily
import com.example.ui.theme.uiText

@Composable
fun AskUserDialog(
    request: AskUserRequest,
    onRespond: (String) -> Unit
) {
    val uiSettings = LocalUISettings.current
    val fs = uiSettings.fontSizeScale
    val resolvedFontFamily = resolveFontFamily(uiSettings.fontFamily)
    val cornerRadius = uiSettings.cornerRadiusDp.dp

    var customInput by remember { mutableStateOf("") }
    var selectedOptions by remember { mutableStateOf(setOf<String>()) }

    Dialog(
        onDismissRequest = {
            // Non-dismissable by clicking outside to ensure model receives a response
        },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Card(
            shape = RoundedCornerShape(cornerRadius),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxSize()
            ) {
                // Header
                Text(
                    text = uiText("dialog.ask_user.title", "需要您的澄清与确认"),
                    fontSize = (18 * fs).sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = resolvedFontFamily,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Question Area (scrollable in case it's long)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = request.question,
                            fontSize = (14 * fs).sp,
                            fontFamily = resolvedFontFamily,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = (20 * fs).sp
                        )

                        if (request.options.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = uiText("dialog.ask_user.options_hint", "请选择以下选项："),
                                fontSize = (12 * fs).sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = resolvedFontFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            // Predefined options
                            if (request.multiSelect) {
                                // Multi-select mode: checkboxes
                                request.options.forEach { option ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = selectedOptions.contains(option),
                                            onCheckedChange = { checked ->
                                                selectedOptions = if (checked) {
                                                    selectedOptions + option
                                                } else {
                                                    selectedOptions - option
                                                }
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = option,
                                            fontSize = (13 * fs).sp,
                                            fontFamily = resolvedFontFamily,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            } else {
                                // Single-select mode: buttons
                                request.options.forEach { option ->
                                    OutlinedButton(
                                        onClick = { onRespond(option) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        shape = RoundedCornerShape((uiSettings.cornerRadiusDp - 2).coerceAtLeast(0).dp)
                                    ) {
                                        Text(
                                            text = option,
                                            fontSize = (13 * fs).sp,
                                            fontFamily = resolvedFontFamily,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Custom input text field
                OutlinedTextField(
                    value = customInput,
                    onValueChange = { customInput = it },
                    label = { Text(uiText("dialog.ask_user.custom_input_label", "自定义回答"), fontFamily = resolvedFontFamily) },
                    placeholder = { Text(uiText("dialog.ask_user.custom_input_placeholder", "在此输入您的自定义回答..."), fontFamily = resolvedFontFamily) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = resolvedFontFamily, fontSize = (13 * fs).sp),
                    maxLines = 4
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onRespond("User cancelled the clarification request.") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape((uiSettings.cornerRadiusDp - 2).coerceAtLeast(0).dp)
                    ) {
                        Text(uiText("action.cancel", "取消"), fontFamily = resolvedFontFamily)
                    }

                    Button(
                        onClick = {
                            if (request.multiSelect && selectedOptions.isNotEmpty()) {
                                val jsonArray = org.json.JSONArray(selectedOptions.toList())
                                onRespond(jsonArray.toString())
                            } else {
                                onRespond(customInput.trim())
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = if (request.multiSelect) {
                            selectedOptions.isNotEmpty() || customInput.trim().isNotEmpty()
                        } else {
                            customInput.trim().isNotEmpty()
                        },
                        shape = RoundedCornerShape((uiSettings.cornerRadiusDp - 2).coerceAtLeast(0).dp)
                    ) {
                        Text(uiText("action.confirm", "确认"), fontFamily = resolvedFontFamily)
                    }
                }
            }
        }
    }
}
