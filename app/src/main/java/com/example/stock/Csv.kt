package com.example.stock

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CSV 备份/恢复。
 *
 * 导出格式（第一行为表头）：
 *   名称,分类,单位,变动,备注,时间
 * 变动为正=入库，为负=出库；时间格式 yyyy-MM-dd HH:mm:ss。
 *
 * 导入是"追加"语义：按名称匹配已有物品，匹配不到就新建，然后逐条写入流水。
 * 因此导入同一份文件两次会让数量翻倍——这是刻意保留原始流水的结果。
 */
object Csv {

    private const val HEADER = "名称,分类,单位,变动,备注,时间"
    private const val TIME_FMT = "yyyy-MM-dd HH:mm:ss"

    private fun esc(s: String): String {
        val needQuote = s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        val body = s.replace("\"", "\"\"")
        return if (needQuote) "\"$body\"" else body
    }

    fun buildCsv(rows: List<TxnWithName>): String {
        val fmt = SimpleDateFormat(TIME_FMT, Locale.getDefault())
        val sb = StringBuilder()
        sb.append(HEADER).append("\r\n")
        rows.forEach { r ->
            sb.append(esc(r.name)).append(',')
                .append(esc(r.category)).append(',')
                .append(esc(r.unit)).append(',')
                .append(r.delta).append(',')
                .append(esc(r.note)).append(',')
                .append(fmt.format(Date(r.time))).append("\r\n")
        }
        return sb.toString()
    }

    /** 返回导出的流水条数 */
    suspend fun export(ctx: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val rows = Db.get(ctx).dao().exportRows()
        val text = buildCsv(rows)
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            // 带 BOM，方便 Excel 正确识别 UTF-8 中文
            out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
        } ?: throw IllegalStateException("无法写入所选文件")
        rows.size
    }

    /** 返回 (成功条数, 跳过条数) */
    suspend fun import(ctx: Context, uri: Uri): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val raw = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("无法读取所选文件")
        var text = String(raw, Charsets.UTF_8)
        if (text.startsWith("\uFEFF")) text = text.substring(1)

        val parsed = parseCsv(text)
        var ok = 0
        var bad = 0
        val fmt = SimpleDateFormat(TIME_FMT, Locale.getDefault())
        val dao = Db.get(ctx).dao()

        for ((idx, cols) in parsed.withIndex()) {
            if (idx == 0 && cols.firstOrNull()?.trim() == "名称") continue
            if (cols.size < 4) { bad++; continue }
            val name = cols[0].trim()
            val category = cols[1].trim()
            val unit = cols[2].trim().ifBlank { "个" }
            val delta = cols[3].trim().toIntOrNull()
            val note = cols.getOrElse(4) { "" }.trim()
            val timeStr = cols.getOrElse(5) { "" }.trim()
            if (name.isEmpty() || delta == null || delta == 0) { bad++; continue }
            val time = try {
                if (timeStr.isEmpty()) System.currentTimeMillis() else fmt.parse(timeStr)!!.time
            } catch (e: Exception) {
                System.currentTimeMillis()
            }
            dao.importRow(name, category, unit, delta, note, time)
            ok++
        }
        ok to bad
    }

    /** 支持双引号包裹、内部 "" 转义、字段内换行的标准 CSV 解析 */
    private fun parseCsv(text: String): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        val n = text.length

        fun endField() { row.add(field.toString()); field.setLength(0) }
        fun endRow() {
            endField()
            if (row.any { it.isNotEmpty() }) out.add(row)
            row = mutableListOf()
        }

        while (i < n) {
            val c = text[i]
            if (inQuotes) {
                when {
                    c == '"' && i + 1 < n && text[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> endField()
                    '\r' -> { }
                    '\n' -> endRow()
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return out
    }
}