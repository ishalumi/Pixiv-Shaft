package ceui.pixiv.db.synonym

import androidx.room.Embedded
import androidx.room.Relation

/**
 * 目标标签 + 它名下的全部同义词，一次性查出（管理页树形列表 / 详情页匹配框共用）。
 */
data class TargetWithSynonyms(
    @Embedded val target: SynonymTargetEntity,
    @Relation(parentColumn = "id", entityColumn = "targetId")
    val synonyms: List<SynonymTagEntity>,
)
