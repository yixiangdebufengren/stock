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
    val id: Long,
    val name: String,
    val category: String,
    val unit: String,
    val qty: Int
)

/** 流水 + 物品名，用于导出 CSV */
data class TxnWithName(
    val id: Long,
    val name: String,
    val category: String,
    val unit: String,
    val delta: Int,
    val note: String,
    val time: Long
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

    @Query("SELECT * FROM Txn WHERE itemId = :id ORDER BY time DESC")
    abstract fun history(id: Long): Flow<List<Txn>>

    @Query("SELECT * FROM Item WHERE name = :n LIMIT 1")
    abstract suspend fun byName(n: String): Item?

    @Query("SELECT * FROM Item WHERE id = :id LIMIT 1")
    abstract suspend fun itemById(id: Long): Item?

    @Query("SELECT * FROM Item ORDER BY name")
    abstract suspend fun allItems(): List<Item>

    @Query("SELECT * FROM Txn WHERE id = :id LIMIT 1")
    abstract suspend fun txnById(id: Long): Txn?

    @Query("SELECT COALESCE(SUM(delta),0) FROM Txn WHERE itemId = :itemId")
    abstract suspend fun qtyOf(itemId: Long): Int

    @Query("""SELECT t.id AS id, i.name AS name, i.category AS category, i.unit AS unit,
                     t.delta AS delta, t.note AS note, t.time AS time
              FROM Txn t INNER JOIN Item i ON i.id = t.itemId
              ORDER BY t.time ASC""")
    abstract suspend fun exportRows(): List<TxnWithName>

    @Insert
    abstract suspend fun insertItem(i: Item): Long

    @Insert
    abstract suspend fun insertTxn(t: Txn): Long

    @Delete
    abstract suspend fun deleteTxn(t: Txn)

    @Update
    abstract suspend fun updateItem(i: Item)

    /** 物品不存在则自动创建，然后写一条流水。返回流水 id。 */
    @Transaction
    open suspend fun record(name: String, category: String, delta: Int, note: String): Long {
        val n = name.trim()
        val existing = byName(n)
        val id = existing?.id ?: insertItem(Item(name = n, category = category.trim()))
        return insertTxn(Txn(itemId = id, delta = delta, note = note.trim()))
    }

    /** 撤销一条流水 */
    @Transaction
    open suspend fun undo(txnId: Long) {
        txnById(txnId)?.let { deleteTxn(it) }
    }

    /** 导入 CSV 得到的一条流水：按名字找物品，没有就新建 */
    @Transaction
    open suspend fun importRow(
        name: String,
        category: String,
        unit: String,
        delta: Int,
        note: String,
        time: Long
    ) {
        val n = name.trim()
        if (n.isEmpty()) return
        val existing = byName(n)
        val id = if (existing == null) {
            insertItem(Item(name = n, category = category.trim(), unit = unit.ifBlank { "个" }))
        } else {
            // 分类为空时用导入数据补上，其余不改
            if (existing.category.isBlank() && category.isNotBlank()) {
                updateItem(existing.copy(category = category.trim()))
            }
            existing.id
        }
        insertTxn(Txn(itemId = id, delta = delta, note = note.trim(), time = time))
    }
}

@Database(entities = [Item::class, Txn::class], version = 1, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): StockDao

    companion object {
        @Volatile
        private var inst: Db? = null

        fun get(c: Context): Db = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, Db::class.java, "stock.db")
                .build()
                .also { inst = it }
        }
    }
}