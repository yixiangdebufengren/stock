package com.example.stock

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(indices = [Index("name", unique = true)])
data class Item(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: String = "",
    val unit: String = "个",
    @ColumnInfo(defaultValue = "0") val minQty: Int = 0
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
    val id: Long, val name: String, val category: String, val unit: String, val qty: Int,
    val minQty: Int = 0
)

/** 流水 + 物品信息，用于导出 CSV */
data class ExportRow(
    val name: String, val category: String, val unit: String, val qty: Int
)

data class TxnExportRow(
    val name: String, val category: String, val delta: Int, val note: String, val time: Long
)

@Dao
abstract class StockDao {
    @Query(
        """SELECT i.id, i.name, i.category, i.unit, i.minQty AS minQty,
                  COALESCE(SUM(t.delta),0) AS qty
           FROM Item i LEFT JOIN Txn t ON t.itemId = i.id
           WHERE i.name LIKE '%' || :q || '%' OR i.category LIKE '%' || :q || '%'
           GROUP BY i.id ORDER BY i.category, i.name"""
    )
    abstract fun stock(q: String): Flow<List<StockRow>>

    @Query(
        """SELECT i.name AS name, i.category AS category, i.unit AS unit,
                  COALESCE(SUM(t.delta),0) AS qty
           FROM Item i LEFT JOIN Txn t ON t.itemId = i.id
           GROUP BY i.id ORDER BY i.category, i.name"""
    )
    abstract suspend fun exportStock(): List<ExportRow>

    @Query(
        """SELECT i.name AS name, i.category AS category, t.delta AS delta,
                  t.note AS note, t.time AS time
           FROM Txn t INNER JOIN Item i ON i.id = t.itemId
           ORDER BY t.time ASC, t.id ASC"""
    )
    abstract suspend fun exportTxns(): List<TxnExportRow>

    @Query("SELECT * FROM Txn WHERE itemId = :id ORDER BY time DESC, id DESC")
    abstract fun history(id: Long): Flow<List<Txn>>

    @Query("SELECT * FROM Item WHERE name = :n LIMIT 1")
    abstract suspend fun byName(n: String): Item?

    @Insert abstract suspend fun insertItem(i: Item): Long
    @Insert abstract suspend fun insertTxn(t: Txn): Long
    @Insert abstract suspend fun insertTxns(list: List<Txn>): List<Long>

    @Query("DELETE FROM Txn WHERE id IN (:ids)")
    abstract suspend fun deleteTxns(ids: List<Long>)

    @Query("UPDATE Item SET name = :name, category = :cat, unit = :unit, minQty = :min WHERE id = :id")
    abstract suspend fun updateItem(id: Long, name: String, cat: String, unit: String, min: Int)

    @Query("DELETE FROM Item WHERE id = :id")
    abstract suspend fun deleteItem(id: Long)

    @Query("SELECT * FROM Item WHERE id = :id LIMIT 1")
    abstract suspend fun itemById(id: Long): Item?

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

@Database(entities = [Item::class, Txn::class], version = 2, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): StockDao

    companion object {
        @Volatile private var inst: Db? = null

        /** 1 → 2：只有 Item 增加了 minQty 字段，已有数据全部保留 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Item ADD COLUMN minQty INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(c: Context): Db = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, Db::class.java, "stock.db")
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { inst = it }
        }
    }
}