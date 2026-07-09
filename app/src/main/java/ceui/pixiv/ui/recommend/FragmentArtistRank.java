package ceui.pixiv.ui.recommend;

import android.os.Bundle;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.UAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyUserPreviewBinding;
import ceui.lisa.fragments.NetListFragment;
import ceui.lisa.model.ListUser;
import ceui.lisa.models.UserPreviewsBean;
import ceui.lisa.utils.Params;

/**
 * 画师排行 —— 打自建 shaft-api-v2 的 discover/artists,含 R-18。两种口径由 sort 决定:
 *   total = 总收藏榜(按全部作品 total_bookmarks 求和)
 *   avg   = 平均收藏榜/质量派(按平均 total_bookmarks,作品≥20)
 * 列表复用推荐用户那套 UAdapter：每行 = 画师头像/名字 + 3 张代表作缩略图,点头像开画师主页
 * (UAdapter/PixivOperate 走 Intent,不依赖 NavController,能在 TemplateActivity 承载)。
 */
public class FragmentArtistRank extends NetListFragment<FragmentBaseListBinding,
        ListUser, UserPreviewsBean> {

    private String sort = "total";

    public static FragmentArtistRank newInstance(String sort) {
        FragmentArtistRank frag = new FragmentArtistRank();
        Bundle args = new Bundle();
        args.putString(Params.DATA_TYPE, sort);
        frag.setArguments(args);
        return frag;
    }

    @Override
    public void initBundle(Bundle bundle) {
        String raw = bundle.getString(Params.DATA_TYPE);
        sort = "avg".equals(raw) ? "avg" : "total";
    }

    @Override
    public RemoteRepo<ListUser> repository() {
        return new ArtistRankRepo(sort);
    }

    @Override
    public BaseAdapter<UserPreviewsBean, RecyUserPreviewBinding> adapter() {
        return new UAdapter(allItems, mContext);
    }

    @Override
    public String getToolbarTitle() {
        return getString("avg".equals(sort)
                ? R.string.artist_avg_rank_title
                : R.string.artist_rank_title);
    }
}
