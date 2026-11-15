package dev.uoftprint.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.uoftprint.R
import dev.uoftprint.core.Duplex
import dev.uoftprint.core.Language
import dev.uoftprint.data.PrinterConfig
import dev.uoftprint.data.PrinterRepository

class MainActivity : ComponentActivity() {
    private val openPrinter = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        openPrinter.value = intent.getStringExtra(EXTRA_PRINTER_ID)
        val repo = PrinterRepository.get(this)
        setContent {
            UofTPrintTheme {
                MainScreen(repo, openPrinter.value, onPrinterOpened = { openPrinter.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_PRINTER_ID)?.let { openPrinter.value = it }
    }

    companion object {
        const val EXTRA_PRINTER_ID = "printer_id"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(repo: PrinterRepository, openPrinterId: String?, onPrinterOpened: () -> Unit) {
    val printers by repo.printers.collectAsStateWithLifecycle()
    val releaseName by repo.releaseName.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var sheetFor by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<PrinterConfig?>(null) }
    val context = LocalContext.current
    val activity = context as ComponentActivity

    if (openPrinterId != null) {
        sheetFor = openPrinterId
        onPrinterOpened()
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) FilePrinter.print(activity, uri)
    }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val builtIn = printers.filter { it.builtIn }
    val custom = printers.filter { !it.builtIn }
    val q = query.trim()
    val shownBuiltIn = if (q.isEmpty()) builtIn else builtIn.filter {
        it.name.contains(q, ignoreCase = true) || it.queue.contains(q, ignoreCase = true)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_PRINT_SETTINGS))
                    }) { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.open_print_settings)) }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
        ) {
            item {
                ReleaseNameField(releaseName, showError = printers.any { it.builtIn && it.enabled }, onChange = repo::setReleaseName)
            }
            item {
                HowToPrint(
                    enabledCount = printers.count { it.enabled },
                    onPrintFile = { pickFile.launch(arrayOf("application/pdf", "image/*")) },
                    onOpenSettings = { context.startActivity(Intent(Settings.ACTION_PRINT_SETTINGS)) },
                )
            }
            item {
                SectionHeader(
                    title = stringResource(R.string.section_uoft),
                    detail = stringResource(R.string.section_uoft_detail, builtIn.count { it.enabled }, builtIn.size),
                )
            }
            item {
                SearchField(query) { query = it }
            }
            items(shownBuiltIn, key = { it.id }) { p ->
                PrinterRow(p, onToggle = { repo.setEnabled(p, it) }, onOpen = { sheetFor = p.id })
            }
            if (shownBuiltIn.isEmpty()) {
                item { EmptyText(stringResource(R.string.search_empty, q)) }
            }
            item {
                SectionHeader(
                    title = stringResource(R.string.section_custom),
                    detail = null,
                    action = {
                        TextButton(onClick = { editing = PrinterRepository.newCustom() }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.add_printer))
                        }
                    },
                )
            }
            items(custom, key = { it.id }) { p ->
                PrinterRow(p, onToggle = { repo.setEnabled(p, it) }, onOpen = { sheetFor = p.id })
            }
            if (custom.isEmpty()) {
                item { EmptyText(stringResource(R.string.custom_empty)) }
            }
        }
    }

    val sheetPrinter = sheetFor?.let { id -> printers.firstOrNull { it.id == id } }
    if (sheetPrinter != null) {
        PrinterSheet(
            printer = sheetPrinter,
            onDismiss = { sheetFor = null },
            onEnabled = { repo.setEnabled(sheetPrinter, it) },
            onDuplex = { repo.setDefaultDuplex(sheetPrinter, it) },
            onDpi = { repo.setDpi(sheetPrinter, it) },
            onEdit = { sheetFor = null; editing = sheetPrinter },
            onDelete = { sheetFor = null; repo.deleteCustom(sheetPrinter) },
        )
    }
    editing?.let { p ->
        CustomPrinterDialog(
            initial = p,
            onDismiss = { editing = null },
            onSave = { repo.saveCustom(it); editing = null },
        )
    }
}

@Composable
private fun ReleaseNameField(value: String, showError: Boolean, onChange: (String) -> Unit) {
    val missing = value.isBlank() && showError
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { v -> onChange(v.filter { it.code < 128 && (it.isLetterOrDigit() || it in "._-") }.take(31)) },
            label = { Text(stringResource(R.string.release_name)) },
            placeholder = { Text(stringResource(R.string.release_name_hint)) },
            supportingText = {
                Text(stringResource(if (missing) R.string.release_name_missing else R.string.release_name_help))
            },
            isError = missing,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun HowToPrint(enabledCount: Int, onPrintFile: () -> Unit, onOpenSettings: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = if (enabledCount == 0) stringResource(R.string.howto_none_title) else stringResource(R.string.howto_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(if (enabledCount == 0) R.string.howto_none_body else R.string.howto_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onPrintFile, enabled = enabledCount > 0) {
                    Icon(painterResource(R.drawable.ic_printer), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.print_file))
                }
                OutlinedButton(onClick = onOpenSettings) { Text(stringResource(R.string.open_print_settings)) }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, detail: String?, action: (@Composable () -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 24.dp, bottom = 4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        action?.invoke()
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(stringResource(R.string.search_hint)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) {
                Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear))
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

@Composable
fun PrinterRow(p: PrinterConfig, onToggle: (Boolean) -> Unit, onOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        QueueTile(p)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                summary(p),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = p.enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun summary(p: PrinterConfig): String {
    val sides = when {
        !p.duplexCapable -> stringResource(R.string.sides_one_only)
        p.defaultDuplex == Duplex.NONE -> stringResource(R.string.sides_one)
        p.defaultDuplex == Duplex.LONG_EDGE -> stringResource(R.string.sides_long)
        else -> stringResource(R.string.sides_short)
    }
    val where = if (p.builtIn) p.queue else "${p.queue}, ${p.host}"
    return "$where  /  $sides"
}

/**
 * The queue tile: the location code as printed on the machine, with a toner stripe underneath:
 * solid black for B&W printers, cyan / magenta / yellow for colour printers.
 */
@Composable
fun QueueTile(p: PrinterConfig, size: Int = 52) {
    val dark = MaterialTheme.colorScheme.onSurface
    val tileBg = if (p.enabled) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceContainer
    Column(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tileBg),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 3.dp), contentAlignment = Alignment.Center) {
            // Multi-part codes stack ("UC" over "CSC"); long single codes shrink to fit.
            val code = p.tileCode.replace('-', '\n')
            val longest = code.lines().maxOf { it.length }
            Text(
                code,
                style = TileText.copy(fontSize = if (longest <= 4) TileText.fontSize else 12.sp, lineHeight = 14.sp),
                textAlign = TextAlign.Center,
                color = if (p.enabled) dark else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Clip,
            )
        }
        Row(Modifier.fillMaxWidth().height(6.dp)) {
            if (p.color) {
                Box(Modifier.weight(1f).fillMaxSize().background(Toner.Cyan))
                Box(Modifier.weight(1f).fillMaxSize().background(Toner.Magenta))
                Box(Modifier.weight(1f).fillMaxSize().background(Toner.Yellow))
            } else {
                Box(Modifier.fillMaxSize().background(dark))
            }
        }
    }
}

@Composable
fun languageLabel(l: Language) = stringResource(if (l == Language.POSTSCRIPT) R.string.lang_ps else R.string.lang_pcl)

@Composable
fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
