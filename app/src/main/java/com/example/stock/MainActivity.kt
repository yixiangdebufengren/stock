package com.example.stock

import android.app.Application
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// ───────────────────────── ViewModel ─────────────────────────
@OptIn(ExperimentalCoroutinesApi::class)
class VM(app: Application) : AndroidViewModel(app) {
    private val dao = Db.get(app).dao()

    val query = MutableStateFlow("")
    val category = MutableStateFlow("")   // 空字符串 = 全部

    /** 全部物品（不受搜索影响）：用于弹窗、批量页、分类条 */
    val all = dao.stock("")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 主页列表：搜索 + 分类筛选 */
    val rows = combine(query.flatMapLatest { dao.stock(it.trim()) }, category) { list, c ->
        if (c.isBlank()) list else list.filter { it.category == c }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun history(id: Long) = dao.history(id)

    fun create(name: String, cat: String, unit: String, initial: Int, onResult: (Boolean) -> Unit) {
        viewModelScope.launch { onResult(dao.createItem(name, cat, unit, initial)) }
    }

    /** 单个出入库也走这里：lines = mapOf(itemId to 数量) */
    fun apply(lines: Map<Long, Int>, sign: Int, note: String, onDone: (List<Long>) -> Unit) {
        viewModelScope.launch { onDone(dao.recordBatch(lines, sign, note)) }
    }

    fun undo(ids: List<Long>) {
        viewModelScope.launch { dao.deleteTxns(ids) }
    }
}

// ───────────────────────── Activity ─────────────────────────
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val scheme = if (Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            } else if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) { Surface { App() } }
        }
    }
}

// ───────────────────────── 主页 ─────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: VM = viewModel()) {
    val rows by vm.rows.collectAsState()
    val all by vm.all.collectAsState()
    val q by vm.query.collectAsState()
    val cat by vm.category.collectAsState()

    var showNew by remember { mutableStateOf(false) }
    var batch by remember { mutableStateOf(false) }
    var detailId by remember { mutableStateOf<Long?>(null) }

    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun toast(msg: String, ids: List<Long>) {
        scope.launch {
            snack.currentSnackbarData?.dismiss()
            val r = snack.showSnackbar(
                message = msg,
                actionLabel = if (ids.isNotEmpty()) "撤销" else null,
                duration = SnackbarDuration.Long
            )
            if (r == SnackbarResult.ActionPerformed) vm.undo(ids)
        }
    }

    BackHandler(enabled = batch) { batch = false }

    if (batch) {
        BatchScreen(vm, onClose = { batch = false }) { m, ids -> toast(m, ids) }
        return
    }

    val cats = remember(all) { all.map { it.category }.filter { it.isNotBlank() }.distinct() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("库存") }) },
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExtendedFloatingActionButton(
                    onClick = { batch = true },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    icon = { Icon(Icons.Default.Checklist, null) },
                    text = { Text("批量出入库") }
                )
                ExtendedFloatingActionButton(
                    onClick = { showNew = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("新增物品") }
                )
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = q, onValueChange = { vm.query.value = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索名称或分类") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true, shape = MaterialTheme.shapes.extraLarge
            )
            if (cats.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    item {
                        FilterChip(selected = cat.isBlank(), onClick = { vm.category.value = "" },
                            label = { Text("全部") })
                    }
                    items(cats) { c ->
                        FilterChip(selected = cat == c,
                            onClick = { vm.category.value = if (cat == c) "" else c },
                            label = { Text(c) })
                    }
                }
            }
            Text(
                "共 ${rows.size} 种物品",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (all.isEmpty()) "还没有物品，点右下角「新增物品」开始" else "没有匹配的物品",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 160.dp)
                ) {
                    items(rows, key = { it.id }) { r ->
                        ElevatedCard(onClick = { detailId = r.id }, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.name, style = MaterialTheme.typography.titleMedium)
                                    if (r.category.isNotBlank())
                                        Text(r.category, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(
                                    "${r.qty} ${r.unit}",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = if (r.qty < 0) MaterialTheme.colorScheme.error
                                            else MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNew) NewItemSheet(vm, cats, onDismiss = { showNew = false }) { m -> toast(m, emptyList()) }

    val detail = all.find { it.id == detailId }
    if (detail != null) {
        AdjustSheet(vm, detail, onDismiss = { detailId = null }) { m, ids -> toast(m, ids) }
    }
}

// ───────────────────────── 通用：入库/出库切换 ─────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InOutToggle(inbound: Boolean, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(inbound, { onChange(true) }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("入库 +") }
        SegmentedButton(!inbound, { onChange(false) }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("出库 −") }
    }
}

// ───────────────────────── 点击物品：出入库弹窗 ─────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdjustSheet(vm: VM, row: StockRow, onDismiss: () -> Unit, onDone: (String, List<Long>) -> Unit) {
    var inbound by remember { mutableStateOf(true) }
    var amt by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    // 注意：Flow 必须 remember，否则每次重组都会重新订阅
    val hist by remember(row.id) { vm.history(row.id) }.collectAsState(emptyList())
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    val n = amt.toIntOrNull() ?: 0
    val after = row.qty + if (inbound) n else -n
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(300); runCatching { fr.requestFocus() } }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 24.dp)
                .navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(row.name, style = MaterialTheme.typography.titleLarge)
            Text(
                "现有 ${row.qty} ${row.unit}",
                style = MaterialTheme.typography.headlineMedium,
                color = if (row.qty < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
            InOutToggle(inbound) { inbound = it }
            OutlinedTextField(
                value = amt, onValueChange = { amt = it.filter(Char::isDigit).take(7) },
                modifier = Modifier.fillMaxWidth().focusRequester(fr),
                label = { Text("数量") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = n > 0 && after < 0,
                supportingText = { if (n > 0) Text("操作后：$after ${row.unit}" + if (after < 0) "（将变为负数）" else "") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 5, 10, 50).forEach { k ->
                    AssistChip(onClick = { amt = (n + k).toString() }, label = { Text("+$k") })
                }
            }
            OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(),
                label = { Text("备注（可选）") }, singleLine = true)
            Button(
                onClick = {
                    val sign = if (inbound) 1 else -1
                    val msg = "${row.name} ${if (inbound) "入库 +" else "出库 −"}$n，现有 $after${row.unit}"
                    vm.apply(mapOf(row.id to n), sign, note) { ids -> onDone(msg, ids) }
                    onDismiss()
                },
                enabled = n > 0, modifier = Modifier.fillMaxWidth()
            ) { Text(if (inbound) "确认入库" else "确认出库") }

            if (hist.isNotEmpty()) {
                HorizontalDivider()
                Text("最近流水", style = MaterialTheme.typography.labelLarge)
                hist.take(8).forEach { t ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(if (t.delta > 0) "+${t.delta}" else "${t.delta}") },
                        supportingContent = { if (t.note.isNotBlank()) Text(t.note) },
                        trailingContent = { Text(fmt.format(Date(t.time))) }
                    )
                }
            }
        }
    }
}

// ───────────────────────── 新增物品弹窗 ─────────────────────────
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewItemSheet(vm: VM, cats: List<String>, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("个") }
    var init by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 24.dp)
                .navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("新增物品", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                name, { name = it; err = null }, Modifier.fillMaxWidth(),
                label = { Text("名称") }, singleLine = true, isError = err != null,
                supportingText = { err?.let { Text(it) } }
            )
            OutlinedTextField(cat, { cat = it }, Modifier.fillMaxWidth(),
                label = { Text("分类（可选）") }, singleLine = true)
            if (cats.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    cats.forEach { c ->
                        FilterChip(selected = cat == c, onClick = { cat = c }, label = { Text(c) })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(unit, { unit = it }, Modifier.weight(1f),
                    label = { Text("单位") }, singleLine = true)
                OutlinedTextField(init, { init = it.filter(Char::isDigit).take(7) }, Modifier.weight(1f),
                    label = { Text("初始数量") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Button(
                onClick = {
                    vm.create(name, cat, unit, init.toIntOrNull() ?: 0) { ok ->
                        if (ok) { onDone("已新增：${name.trim()}"); onDismiss() }
                        else err = "已存在，请直接点列表里的它进行出入库"
                    }
                },
                enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()
            ) { Text("保存") }
        }
    }
}

// ───────────────────────── 批量出入库（全屏页） ─────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchScreen(vm: VM, onClose: () -> Unit, onDone: (String, List<Long>) -> Unit) {
    val all by vm.all.collectAsState()
    var inbound by remember { mutableStateOf(true) }
    var search by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val lines = remember { mutableStateMapOf<Long, Int>() }

    val shown = remember(all, search) {
        val s = search.trim()
        if (s.isEmpty()) all else all.filter { it.name.contains(s, true) || it.category.contains(s, true) }
    }
    val count = lines.count { it.value > 0 }
    val total = lines.values.filter { it > 0 }.sum()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("批量出入库") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, "关闭") } }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(),
                        label = { Text("备注（可选，整批共用）") }, singleLine = true)
                    Button(
                        onClick = {
                            val sign = if (inbound) 1 else -1
                            val msg = "已批量${if (inbound) "入库" else "出库"} $count 项"
                            vm.apply(lines.toMap(), sign, note) { ids -> onDone(msg, ids) }
                            onClose()
                        },
                        enabled = count > 0, modifier = Modifier.fillMaxWidth()
                    ) { Text("提交 $count 项 · 共 $total") }
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InOutToggle(inbound) { inbound = it }
            OutlinedTextField(
                value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索名称或分类") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true, shape = MaterialTheme.shapes.extraLarge
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(shown, key = { it.id }) { r ->
                    val n = lines[r.id] ?: 0
                    BatchRow(r, n, inbound) { v -> if (v <= 0) lines.remove(r.id) else lines[r.id] = v }
                }
            }
        }
    }
}

@Composable
fun BatchRow(r: StockRow, n: Int, inbound: Boolean, onChange: (Int) -> Unit) {
    val after = r.qty + if (inbound) n else -n
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (n > 0) MaterialTheme.colorScheme.primaryContainer
                             else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.titleMedium)
                Text("现有 ${r.qty}${r.unit}", style = MaterialTheme.typography.bodySmall)
                if (n > 0) Text(
                    "→ $after${r.unit}", style = MaterialTheme.typography.labelMedium,
                    color = if (after < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = { onChange(n - 1) }, enabled = n > 0) { Icon(Icons.Default.Remove, "减") }
            OutlinedTextField(
                value = if (n == 0) "" else n.toString(),
                onValueChange = { onChange(it.filter(Char::isDigit).take(6).toIntOrNull() ?: 0) },
                modifier = Modifier.width(76.dp), singleLine = true,
                placeholder = { Text("0", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            IconButton(onClick = { onChange(n + 1) }) { Icon(Icons.Default.Add, "加") }
        }
    }
}