package com.example.stock

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CSV 导出：分「库存汇总」「流水明细」两段，中间空一行。
 * 字段含逗号 / 引号 / 换行时用双引号包裹，内部引号写成两个引号。
 */
object Csv {

    private val FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun cell(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
            "\"" + s.replace("\"", "\"\"") + "\""
        else s

    fun build(items: List<ExportRow>, txns: List<TxnExportRow>): String {
        val sb = StringBuilder()
        sb.append("库存汇总\r\n")
        sb.append("名称,分类,单位,现有数量\r\n")
        items.forEach { r ->
            sb.append(cell(r.name)).append(',')
                .append(cell(r.category)).append(',')
                .append(cell(r.unit)).append(',')
                .append(r.qty).append("\r\n")
        }
        sb.append("\r\n")
        sb.append("流水明细\r\n")
        sb.append("时间,名称,分类,变动,备注\r\n")
        txns.forEach { t ->
            sb.append(FMT.format(Date(t.time))).append(',')
                .append(cell(t.name)).append(',')
                .append(cell(t.category)).append(',')
                .append(t.delta).append(',')
                .append(cell(t.note)).append("\r\n")
        }
        return sb.toString()
    }
}