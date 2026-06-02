package ceui.pixiv.db.synonym

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 同义词词典 —— 同义词标签（issue #904）。
 *
 * 「同义词标签」= 作品本身的标签里，希望被识别为某个目标标签的那些名字。
 * 例如目标标签 "EVA" 下的同义词：新世紀エヴァンゲリオン / エヴァ / Evangelion。
 *
 * [remark] 备注不参与匹配，仅辅助识别（很多 tag 原文只有日语）；
 * 长按作品标签添加同义词时自动填入 tag 译文。
 */
@Entity(
    tableName = "synonym_tag_table",
    indices = [
        Index(value = ["targetId"]),
        Index(value = ["name"]),
    ]
)
data class SynonymTagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 所属目标标签 [SynonymTargetEntity.id] */
    val targetId: Long,
    /** 同义词标签名（作品标签原文），匹配时与作品标签整体比较、不拆空格 */
    val name: String,
    /** 备注（通常是 tag 译文），不参与匹配，可为空 */
    val remark: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)
