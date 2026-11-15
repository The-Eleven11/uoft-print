package dev.uoftprint.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.uoftprint.R
import dev.uoftprint.core.Duplex
import dev.uoftprint.core.Language
import dev.uoftprint.data.PrinterConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterSheet(
    printer: PrinterConfig,
    onDismiss: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onDuplex: (Duplex) -> Unit,
    onDpi: (Int) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                QueueTile(printer, size = 60)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(printer.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.printer_address, printer.queue, printer.host, languageLabel(printer.language)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.toggleable(value = printer.enabled, role = Role.Switch, onValueChange = onEnabled),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.show_in_dialog), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.show_in_dialog_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = printer.enabled, onCheckedChange = null)
            }

            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.default_sides), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            val sides = listOf(
                Duplex.NONE to stringResource(R.string.sides_one_short),
                Duplex.LONG_EDGE to stringResource(R.string.sides_long_short),
                Duplex.SHORT_EDGE to stringResource(R.string.sides_short_short),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                sides.forEachIndexed { i, (mode, label) ->
                    SegmentedButton(
                        selected = printer.defaultDuplex == mode,
                        onClick = { onDuplex(mode) },
                        enabled = printer.duplexCapable || mode == Duplex.NONE,
                        shape = SegmentedButtonDefaults.itemShape(i, sides.size),
                    ) { Text(label, maxLines = 1) }
                }
            }
            Text(
                stringResource(if (printer.duplexCapable) R.string.default_sides_help else R.string.sides_one_only_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            if (printer.color) {
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.quality), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                val qualities = listOf(300 to stringResource(R.string.quality_standard), 600 to stringResource(R.string.quality_high))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    qualities.forEachIndexed { i, (dpi, label) ->
                        SegmentedButton(
                            selected = printer.dpi == dpi,
                            onClick = { onDpi(dpi) },
                            shape = SegmentedButtonDefaults.itemShape(i, qualities.size),
                        ) { Text(label, maxLines = 1) }
                    }
                }
                Text(
                    stringResource(R.string.quality_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            if (!printer.builtIn) {
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onEdit) { Text(stringResource(R.string.edit)) }
                    TextButton(onClick = { confirmDelete = true }) {
                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_title, printer.name)) },
            text = { Text(stringResource(R.string.delete_body)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun CustomPrinterDialog(initial: PrinterConfig, onDismiss: () -> Unit, onSave: (PrinterConfig) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var queue by remember { mutableStateOf(initial.queue) }
    var language by remember { mutableStateOf(initial.language) }
    var color by remember { mutableStateOf(initial.color) }
    var duplex by remember { mutableStateOf(initial.duplexCapable) }

    val portNumber = port.toIntOrNull()
    val hostOk = host.isNotBlank() && host.none { it.isWhitespace() }
    val queueOk = queue.isNotBlank() && queue.all { it in '!'..'~' }
    val portOk = portNumber != null && portNumber in 1..65535
    val valid = hostOk && queueOk && portOk

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial.id.isEmpty()) R.string.add_printer else R.string.edit_printer)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.field_name)) },
                    placeholder = { Text(stringResource(R.string.field_name_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = host, onValueChange = { host = it.trim() }, singleLine = true,
                    label = { Text(stringResource(R.string.field_host)) },
                    placeholder = { Text("192.168.1.20") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = queue, onValueChange = { queue = it.trim() }, singleLine = true,
                        label = { Text(stringResource(R.string.field_queue)) },
                        isError = queue.isNotEmpty() && !queueOk,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, singleLine = true,
                        label = { Text(stringResource(R.string.field_port)) },
                        isError = !portOk,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.field_language), style = MaterialTheme.typography.titleSmall)
                val langs = listOf(Language.POSTSCRIPT, Language.PCL)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    langs.forEachIndexed { i, l ->
                        SegmentedButton(
                            selected = language == l,
                            onClick = { language = l },
                            shape = SegmentedButtonDefaults.itemShape(i, langs.size),
                        ) { Text(languageLabel(l)) }
                    }
                }
                Text(
                    stringResource(R.string.field_language_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ToggleLine(stringResource(R.string.field_color), color && language == Language.POSTSCRIPT, language == Language.POSTSCRIPT) { color = it }
                ToggleLine(stringResource(R.string.field_duplex), duplex, true) { duplex = it }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(
                    initial.copy(
                        name = name.trim().ifEmpty { "$queue @ $host" },
                        host = host, port = portNumber ?: 515, queue = queue,
                        language = language, color = color && language == Language.POSTSCRIPT,
                        duplexCapable = duplex,
                        defaultDuplex = if (duplex) initial.defaultDuplex else Duplex.NONE,
                        dpi = if (language == Language.PCL && initial.id.isEmpty()) 600 else initial.dpi,
                    )
                )
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ToggleLine(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
