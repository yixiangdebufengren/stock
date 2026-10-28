package com.example.stock

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val scheme = if (Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            } else {
                if (dark) darkColorScheme() else lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: VM = viewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val q by vm.query.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val msg by vm.msg.collectAsStateWithLifecycle()

    var showRecord by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showBackup by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<StockRow?>(null) }

    LaunchedEffect(msg) {
        msg?.let {
            snackbar.showSnackbar(it.text)
            vm.clearMsg()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("库存") },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("备份与恢复") },
                            leadingIcon = { Icon(Icons.Default.Backup, null) },
                            onClick = { showMenu = false; showBackup = true }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showRecord = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("记录") }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = q,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索名称或分类") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (q.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "清空")
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge
            )

            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (q.isBlank()) "还没有记录，点右下角「记录」开始" else "没有匹配的物品",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    items(rows, key = { it.id }) { r ->
                        StockCard(r) { detail = r }
                    }
                }
            }
        }
    }

    if (showRecord) RecordSheet(vm) { showRecord = false }
    detail?.let { HistorySheet(vm, it) { detail = null } }
    if (showBackup) BackupSheet(vm) { showBackup = false }
}

@Composable
fun StockCard(r: StockRow, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    r.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (r.category.isNotBlank()) {
                    Text(
                        r.category,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "${r.qty} ${r.unit}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (r.qty < 0) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecordSheet(vm: VM, onDismiss: () -> Unit) {
    val all by vm.all.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var inbound by remember { mutableStateOf(true) }

    val matches = remember(name, all) {
        if (name.isBlank()) all.take(6)
        else all.filter { it.name.contains(name.trim(), ignoreCase = true) }.take(6)
    }
    val exists = all.any { it.name == name.trim() }
    val n = qty.toIntOrNull()
    val ok = name.isNotBlank() && n != null && n > 0

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = inbound,
                    onClick = { inbound = true },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) { Text("入库 +") }
                SegmentedButton(
                    selected = !inbound,
                    onClick = { inbound = false },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text("出库 −") }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("物品名称") },
                singleLine = true
            )

            if (matches.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    matches.forEach { m ->
                        SuggestionChip(
                            onClick = { name = m.name; if (m.category.isNotBlank()) cat = m.category },
                            label = { Text("${m.name} (${m.qty})") }
                        )
                    }
                }
            }

            if (name.isNotBlank() && !exists) {
                OutlinedTextField(
                    value = cat,
                    onValueChange = { cat = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("新物品分类（可选）") },
                    singleLine = true
                )
            }

            OutlinedTextField(
                value = qty,
                onValueChange = { qty = it.filter(Char::isDigit).take(9) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("数量") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number
                )
            )

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("备注（可选）") },
                singleLine = true
            )

            Button(
                onClick = {
                    vm.record(name, cat, if (inbound) n!! else -n!!, note)
                    onDismiss()
                },
                enabled = ok,
                modifier = Modifier.fillMaxWidth()
            ) { Text("确定") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(vm: VM, row: StockRow, onDismiss: () -> Unit) {
    val list by vm.history(row.id).collectAsStateWithLifecycle(emptyList())
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(
                "${row.name} · 当前 ${row.qty}${row.unit}",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(8.dp))
            if (list.isEmpty()) {
                Text(
                    "没有流水记录",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(list, key = { it.id }) { t ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    if (t.delta > 0) "+${t.delta}" else "${t.delta}",
                                    color = if (t.delta > 0) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                                )
                            },
                            supportingContent = {
                                if (t.note.isNotBlank()) Text(t.note)
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        fmt.format(Date(t.time)),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    IconButton(onClick = { vm.undo(t.id) }) {
                                        Icon(
                                            Icons.Default.Undo,
                                            contentDescription = "撤销",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSheet(vm: VM, onDismiss: () -> Unit) {
    val stamp = remember {
        SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let { vm.exportCsv(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.importCsv(it) } }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("备份与恢复", style = MaterialTheme.typography.titleLarge)
            Text(
                "导出为 CSV，可复制到别处保管，换机或重装后能导回来。\n" +
                        "导入是「追加」：不会清空现有数据，同一份文件导两次数量会翻倍。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FilledTonalButton(
                onClick = { exportLauncher.launch("stock_$stamp.csv") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.UploadFile, null)
                Spacer(Modifier.width(8.dp))
                Text("导出 CSV")
            }

            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("text/*", "application/csv", "*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Download, null)
                Spacer(Modifier.width(8.dp))
                Text("导入 CSV")
            }

            Text(
                "小提示：以后换用正式签名的安装包覆盖安装时，系统会拒绝（签名不一致），" +
                        "需要先导出 CSV，卸载重装后再导入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}