package com.example.stock

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 一次性提示消息 */
data class Msg(val text: String, val id: Long = System.nanoTime())

@OptIn(ExperimentalCoroutinesApi::class)
class VM(app: Application) : AndroidViewModel(app) {

    private val dao = Db.get(app).dao()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val rows: StateFlow<List<StockRow>> = _query
        .flatMapLatest { dao.stock(it.trim()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 记录弹窗做名字联想用：全部物品 */
    val all: StateFlow<List<StockRow>> = dao.stock("")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _msg = MutableStateFlow<Msg?>(null)
    val msg: StateFlow<Msg?> = _msg.asStateFlow()

    fun setQuery(q: String) { _query.value = q }

    fun clearMsg() { _msg.value = null }

    fun toast(text: String) { _msg.value = Msg(text) }

    fun history(id: Long) = dao.history(id)

    fun record(name: String, cat: String, delta: Int, note: String) {
        viewModelScope.launch {
            val n = name.trim()
            if (n.isEmpty() || delta == 0) return@launch
            try {
                // 出库不允许超过现有库存，避免出现莫名负数
                if (delta < 0) {
                    val item = dao.byName(n)
                    if (item != null) {
                        val cur = dao.qtyOf(item.id)
                        if (cur + delta < 0) {
                            toast("「${n}」当前只有 $cur，不足 ${-delta}")
                            return@launch
                        }
                    }
                }
                dao.record(n, cat, delta, note)
            } catch (e: Exception) {
                toast("保存失败：${e.message}")
            }
        }
    }

    fun undo(txnId: Long) {
        viewModelScope.launch {
            try {
                dao.undo(txnId)
                toast("已撤销该条流水")
            } catch (e: Exception) {
                toast("撤销失败：${e.message}")
            }
        }
    }

    fun exportCsv(uri: Uri) {
        viewModelScope.launch {
            try {
                val n = Csv.export(getApplication(), uri)
                toast("已导出 $n 条流水")
            } catch (e: Exception) {
                toast("导出失败：${e.message}")
            }
        }
    }

    fun importCsv(uri: Uri) {
        viewModelScope.launch {
            try {
                val (ok, bad) = Csv.import(getApplication(), uri)
                toast(if (bad > 0) "已导入 $ok 条，跳过 $bad 条" else "已导入 $ok 条")
            } catch (e: Exception) {
                toast("导入失败：${e.message}")
            }
        }
    }
}