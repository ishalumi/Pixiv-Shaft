package ceui.pixiv.db.synonym

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 同义词词典 —— 目标标签（issue #904）。
 *
 * 「目标标签」= 用户希望保存在收藏夹中的收藏标签名，例如 "EVA"。
 * 名字不允许含空格（Pixiv 用空格作为标签分隔符）。
 * 词典是全局的：插画 / 漫画 / 小说共用同一套目标标签。
 */
@Entity(
    tableName = "synonym_target_table",
    indices = [Index(value = ["name"], unique = true)]
)
data class SynonymTargetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 目标标签名（即收藏夹标签名），全局唯一，不允许空格 */
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)
