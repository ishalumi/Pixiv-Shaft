package ceui.lisa.viewmodel;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

public class SearchModel extends ViewModel {

    //关键字
    private final MutableLiveData<String> keyword = new MutableLiveData<>();
    //收藏数
    private final MutableLiveData<String> starSize = new MutableLiveData<>();
    //关键字匹配模式
    private final MutableLiveData<String> searchType = new MutableLiveData<>();

    //排序模式
    private final MutableLiveData<String> sortType = new MutableLiveData<>();
    //上一个排序模式
    private final MutableLiveData<String> lastSortType = new MutableLiveData<>();

    //开始日期 —— 与 durationBucket 互斥:bucket 非空时这两个字段保持 null
    private final MutableLiveData<String> startDate = new MutableLiveData<>();
    //结束日期
    private final MutableLiveData<String> endDate = new MutableLiveData<>();

    // 投稿期间相对预设档名(DurationBucket.name);非空时 Repo.initApi 当场算 today−N 覆盖
    // start/end_date,跨午夜也不会窗口停滞
    private final MutableLiveData<String> durationBucket = new MutableLiveData<>();

    private final MutableLiveData<String> nowGo = new MutableLiveData<>();

    private final MutableLiveData<Boolean> isNovel = new MutableLiveData<>();

    private final MutableLiveData<Boolean> isPremium = new MutableLiveData<>();

    private final MutableLiveData<Integer> r18Restriction = new MutableLiveData<>();

    // ── V3 filter 维度 —— 老版 FragmentFilter 没暴露但 pixiv API 都吃 ──
    private final MutableLiveData<Integer> bookmarkMin = new MutableLiveData<>();
    private final MutableLiveData<String> tool = new MutableLiveData<>();
    private final MutableLiveData<Integer> genre = new MutableLiveData<>();
    private final MutableLiveData<String> lang = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isOriginalOnly = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isReplaceableOnly = new MutableLiveData<>();
    // 长宽比（仅 illust/manga）—— V3 sheet 写入，老 FragmentFilter 不暴露
    private final MutableLiveData<String> ratioPattern = new MutableLiveData<>();
    // 分辨率档位（仅 illust/manga）—— 4 个独立 query 参数，由 V3 sheet 写入
    private final MutableLiveData<Integer> widthMin = new MutableLiveData<>();
    private final MutableLiveData<Integer> widthMax = new MutableLiveData<>();
    private final MutableLiveData<Integer> heightMin = new MutableLiveData<>();
    private final MutableLiveData<Integer> heightMax = new MutableLiveData<>();
    // 正文长度 / 阅读用时（仅 novel）—— mockup 参数名 text_length/word_count/reading_time
    private final MutableLiveData<Integer> textLengthMin = new MutableLiveData<>();
    private final MutableLiveData<Integer> textLengthMax = new MutableLiveData<>();
    private final MutableLiveData<Integer> wordCountMin = new MutableLiveData<>();
    private final MutableLiveData<Integer> wordCountMax = new MutableLiveData<>();
    private final MutableLiveData<Integer> readingTimeMin = new MutableLiveData<>();
    private final MutableLiveData<Integer> readingTimeMax = new MutableLiveData<>();

    public MutableLiveData<String> getKeyword() {
        return keyword;
    }

    public MutableLiveData<String> getStarSize() {
        return starSize;
    }


    public MutableLiveData<String> getSearchType() {
        return searchType;
    }

    public MutableLiveData<String> getSortType() {
        return sortType;
    }


    public MutableLiveData<String> getStartDate() {
        return startDate;
    }

    public MutableLiveData<String> getEndDate() {
        return endDate;
    }

    public MutableLiveData<String> getDurationBucket() {
        return durationBucket;
    }

    public MutableLiveData<String> getLastSortType() {
        return lastSortType;
    }

    public MutableLiveData<String> getNowGo() {
        return nowGo;
    }

    public MutableLiveData<Boolean> getIsNovel() {
        return isNovel;
    }

    public MutableLiveData<Boolean> getIsPremium() {
        return isPremium;
    }

    public MutableLiveData<Integer> getR18Restriction() {
        return r18Restriction;
    }

    public MutableLiveData<Integer> getBookmarkMin() {
        return bookmarkMin;
    }

    public MutableLiveData<String> getTool() {
        return tool;
    }

    public MutableLiveData<Integer> getGenre() {
        return genre;
    }

    public MutableLiveData<String> getLang() {
        return lang;
    }

    public MutableLiveData<Boolean> getIsOriginalOnly() {
        return isOriginalOnly;
    }

    public MutableLiveData<Boolean> getIsReplaceableOnly() {
        return isReplaceableOnly;
    }

    public MutableLiveData<String> getRatioPattern() {
        return ratioPattern;
    }

    public MutableLiveData<Integer> getWidthMin() {
        return widthMin;
    }

    public MutableLiveData<Integer> getWidthMax() {
        return widthMax;
    }

    public MutableLiveData<Integer> getHeightMin() {
        return heightMin;
    }

    public MutableLiveData<Integer> getHeightMax() {
        return heightMax;
    }

    public MutableLiveData<Integer> getTextLengthMin() {
        return textLengthMin;
    }

    public MutableLiveData<Integer> getTextLengthMax() {
        return textLengthMax;
    }

    public MutableLiveData<Integer> getWordCountMin() {
        return wordCountMin;
    }

    public MutableLiveData<Integer> getWordCountMax() {
        return wordCountMax;
    }

    public MutableLiveData<Integer> getReadingTimeMin() {
        return readingTimeMin;
    }

    public MutableLiveData<Integer> getReadingTimeMax() {
        return readingTimeMax;
    }
}
