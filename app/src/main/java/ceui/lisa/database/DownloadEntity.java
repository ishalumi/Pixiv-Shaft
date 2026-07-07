package ceui.lisa.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import java.io.Serializable;

@Entity(tableName = "illust_download_table",
        indices = {@Index("illustId")})
public final class DownloadEntity implements Serializable {

    @PrimaryKey()
    @NonNull
    private String fileName = "";
    private String filePath = "";
    private String taskGson;
    private String illustGson;
    private long downloadTime;

    /**
     * 作品自身 id（插画 / 小说），v38 新增的索引列。之前判断“这幅画下过没”要对 illustGson
     * blob 做全表 LIKE 扫描（30000+ 行 2GB+ 单次几百 ms~秒级、烧 CPU 又占读连接），加了这列
     * 走索引后 O(log n)。插入时由 {@link DownloadIdExtractor} 从 illustGson 算出（或调用方
     * 直接 set 已知 id）；存量行由 {@code DownloadIdBackfill} 一次性后台回填。
     * 0 = 尚未回填，-1 = 解析失败 / 无 id。
     *
     * 刻意不加 @ColumnInfo(defaultValue)：迁移用 ADD COLUMN ... DEFAULT 0 给存量行补默认值，
     * 但 entity 侧不声明默认——与本仓已上线的 synonym_target_table.lastUsedAt(v37) 同款，
     * 这样 Room 的 schema 校验跳过 default 比对（fresh install 无默认 / 升级有默认都能过）。
     */
    private long illustId;

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    @Override
    public String toString() {
        return "DownloadEntity{" +
                ", taskGson='" + taskGson + '\'' +
                ", illustGson='" + illustGson + '\'' +
                ", downloadTime=" + downloadTime +
                '}';
    }

    public String getTaskGson() {
        return taskGson;
    }

    public void setTaskGson(String taskGson) {
        this.taskGson = taskGson;
    }

    public String getIllustGson() {
        return illustGson;
    }

    public void setIllustGson(String illustGson) {
        this.illustGson = illustGson;
    }

    public long getDownloadTime() {
        return downloadTime;
    }

    public void setDownloadTime(long downloadTime) {
        this.downloadTime = downloadTime;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getIllustId() {
        return illustId;
    }

    public void setIllustId(long illustId) {
        this.illustId = illustId;
    }
}
