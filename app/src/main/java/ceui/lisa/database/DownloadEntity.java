package ceui.lisa.database;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
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
     * @ColumnInfo(defaultValue="0") 必须与迁移的 ADD COLUMN ... DEFAULT 0 保持一致：
     * entity 声明默认 → fresh install 的 CREATE TABLE 与升级迁移都带 DEFAULT 0，schema
     * 校验一致（真机 v37→v38 验证通过）。**改这里等于改 v38 identity hash，改了必须升版本号**，
     * 否则已迁到 v38 的设备会报 "Room cannot verify data integrity"（hash 不匹配）崩。
     */
    @ColumnInfo(defaultValue = "0")
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
