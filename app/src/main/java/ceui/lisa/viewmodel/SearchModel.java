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

    //开始日期
    private final MutableLiveData<String> startDate = new MutableLiveData<>();
    //结束日期
    private final MutableLiveData<String> endDate = new MutableLiveData<>();

    private final MutableLiveData<String> nowGo = new MutableLiveData<>();

    private final MutableLiveData<Boolean> isNovel = new MutableLiveData<>();

    private final MutableLiveData<Boolean> isPremium = new MutableLiveData<>();

    private final MutableLiveData<Integer> r18Restriction = new MutableLiveData<>();

    // ── V3 filter 维度 —— 老版 FragmentFilter 没暴露但 pixiv API 都吃 ──
    private final MutableLiveData<Integer> bookmarkMin = new MutableLiveData<>();
    private final MutableLiveData<String> tool = new MutableLiveData<>();
    private final MutableLiveData<Integer> genre = new MutableLiveData<>();
    private final MutableLiveData<String> lang = new MutableLiveData<>();
    private final MutableLiveData<String> duration = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isOriginalOnly = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isReplaceableOnly = new MutableLiveData<>();
    // 长宽比（仅 illust/manga）—— V3 sheet 写入，老 FragmentFilter 不暴露
    private final MutableLiveData<String> ratioPattern = new MutableLiveData<>();
    // 分辨率档位（仅 illust/manga）—— 4 个独立 query 参数，由 V3 sheet 写入
    private final MutableLiveData<Integer> widthMin = new MutableLiveData<>();
    private final MutableLiveData<Integer> widthMax = new MutableLiveData<>();
    private final MutableLiveData<Integer> heightMin = new MutableLiveData<>();
    private final MutableLiveData<Integer> heightMax = new MutableLiveData<>();

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

    public MutableLiveData<String> getDuration() {
        return duration;
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
}
