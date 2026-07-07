package ceui.lisa.database;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;

/**
 * {@link DownloadDao#getDownloadsNeedingIdBackfill} 的投影行：只取回填 illustId 要用的
 * fileName（主键，UPDATE 定位用）+ illustGson（抽 id 用）。不带 taskGson 等无关列。
 */
public class DownloadIdRow {

    @NonNull
    @ColumnInfo(name = "fileName")
    public String fileName = "";

    @ColumnInfo(name = "illustGson")
    public String illustGson;
}
