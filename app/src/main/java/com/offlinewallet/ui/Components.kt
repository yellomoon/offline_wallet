package com.offlinewallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ScreenColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        content = content,
    )
}

@Composable
fun Eyebrow(text: String, color: Color = Muted) {
    Text(text.uppercase(), color = color, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
}

@Composable
fun Headline(text: String) {
    Text(text, color = Ivory, style = androidx.compose.material3.MaterialTheme.typography.headlineLarge)
}

@Composable
fun Body(text: String, color: Color = Dim) {
    Text(text, color = color, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Ivory, contentColor = Ink, disabledContainerColor = Line, disabledContentColor = Muted),
    ) { Text(text, style = androidx.compose.material3.MaterialTheme.typography.labelLarge) }
}

@Composable
fun SecondaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ivory),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
    ) { Text(text) }
}

@Composable
fun QuietButton(text: String, color: Color = Dim, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(text, color = color) }
}

@Composable
fun Notice(text: String, tone: Color = Copper) {
    Text(
        text,
        color = tone,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(tone.copy(alpha = 0.12f))
            .padding(14.dp),
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
    )
}

@Composable
fun AddressCard(
    label: String,
    address: String,
    accent: Color,
    detail: String? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Raised)
            .border(1.dp, if (selected) accent else Line, RoundedCornerShape(20.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = accent, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            if (onCopy != null) {
                Text("复制", color = Ivory, modifier = Modifier.clickable(onClick = onCopy), fontWeight = FontWeight.Medium)
            }
            if (onRemove != null) {
                Text("移除", color = Muted, modifier = Modifier.padding(start = 16.dp).clickable(onClick = onRemove), fontWeight = FontWeight.Medium)
            }
        }
        if (!detail.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(detail, color = Dim, fontSize = 13.sp)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            displayAddress(address),
            color = Ivory,
            fontFamily = Mono,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun ChoiceCard(title: String, detail: String, accent: Color, selected: Boolean = false, badge: String? = null, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) accent.copy(alpha = 0.12f) else Raised)
            .border(1.dp, if (selected) accent else Line, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Ivory, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (badge != null) Text(badge, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(6.dp))
        Text(detail, color = Dim, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun Slip(lines: List<Pair<String, String>>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Raised)
            .border(1.dp, Line, RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        lines.forEach { (label, value) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Text(label, color = Muted, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (value.length > 20) displayAddress(value) else value,
                    color = Ivory,
                    fontFamily = if (value.length > 18) Mono else FontFamily.Default,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
fun WalletField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    minLines: Int = 1,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardType: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        minLines = minLines,
        visualTransformation = visualTransformation,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Ivory,
            unfocusedBorderColor = Line,
            focusedTextColor = Ivory,
            unfocusedTextColor = Ivory,
            cursorColor = Copper,
            focusedLabelColor = Dim,
            unfocusedLabelColor = Muted,
            focusedContainerColor = Raised,
            unfocusedContainerColor = Raised,
        ),
    )
}

@Composable
fun BusyBar(visible: Boolean, color: Color) {
    if (visible) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = color, trackColor = Line)
}

fun displayAddress(address: String): String = address.asSequence().joinToString("\u200B")

@Composable
fun Gap(size: Int = 16) = Spacer(Modifier.height(size.dp))

@Composable
fun RowButtons(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}
