package com.example.stock

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(indices = [Index("name", unique = true)])
data class Item(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: String = "",
    val unit: String = "个"
)

@Entity(
    indices = [Index("itemId")],
    foreignKeys = [ForeignKey(Item::class, ["id"], ["itemId"], onDelete = ForeignKey.CASCADE)]
)
data class Txn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val delta: Int,
    val note: String = "",
    val time: Long = System.currentTimeMillis()
)

data class StockRow(
    val id: Long, val name: String, val category: String, val unit: String, val qty: Int
)

@Dao
abstract class StockDao {
    @Query(
        """SELECT i.id, i.name, i.category, i.unit, COALESCE(SUM(t.delta),0) AS qty
           FROM Item i LEFT JOIN Txn t ON t.itemId = i.id
           WHERE i.name LIKE '%' || :q || '%' OR i.category LIKE '%' || :q || '%'
           GROUP BY i.id ORDER BY i.category, i.name"""
    )
    abstract fun stock(q: String): Flow<List<StockRow>>

    @Query("SELECT * FROM Txn WHERE itemId = :id ORDER BY time DESC, id DESC")
    abstract fun history(id: Long): Flow<List<Txn>>

    @Query("SELECT * FROM Item WHERE name = :n LIMIT 1")
    abstract suspend fun byName(n: String): Item?

    @Insert abstract suspend fun insertItem(i: Item): Long
    @Insert abstract suspend fun insertTxn(t: Txn): Long
    @Insert abstract suspend fun insertTxns(list: List<Txn>): List<Long>

    @Query("DELETE FROM Txn WHERE id IN (:ids)")
    abstract suspend fun deleteTxns(ids: List<Long>)

    /** 新增物品；名称已存在返回 false。initial>0 时写一条「初始库存」流水 */
    @Transaction
    open suspend fun createItem(name: String, category: String, unit: String, initial: Int): Boolean {
        val n = name.trim()
        if (byName(n) != null) return false
        val id = insertItem(Item(name = n, category = category.trim(), unit = unit.trim().ifBlank { "个" }))
        if (initial > 0) insertTxn(Txn(itemId = id, delta = initial, note = "初始库存"))
        return true
    }

    /** 单个/批量出入库统一入口。sign: 入库=1，出库=-1。返回新写入的流水 id（用于撤销） */
    @Transaction
    open suspend fun recordBatch(lines: Map<Long, Int>, sign: Int, note: String): List<Long> {
        val now = System.currentTimeMillis()
        val list = lines.filter { it.value > 0 }.map {
            Txn(itemId = it.key, delta = it.value * sign, note = note.trim(), time = now)
        }
        return if (list.isEmpty()) emptyList() else insertTxns(list)
    }
}

@Database(entities = [Item::class, Txn::class], version = 1, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): StockDao

    companion object {
        @Volatile private var inst: Db? = null
        fun get(c: Context): Db = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, Db::class.java, "stock.db")
                .build().also { inst = it }
        }
    }
}