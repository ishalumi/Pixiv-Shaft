package ceui.pixiv.ui.recommend;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.UAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyUserPreviewBinding;
import ceui.lisa.fragments.NetListFragment;
import ceui.lisa.model.ListUser;
import ceui.lisa.models.UserPreviewsBean;

/**
 * 画师收藏总榜 —— 打自建 shaft-api-v2 的 discover/artists,按画师全部作品的 pixiv 总收藏数
 * 求和排名(含 R-18)。列表复用推荐用户那套 UAdapter：每行 = 画师头像/名字 + 3 张代表作缩略图,
 * 点头像开画师主页、点缩略图开作品详情(UAdapter/PixivOperate 原生走 Intent,不依赖 NavController,
 * 所以能在 TemplateActivity 里承载)。
 */
public class FragmentArtistRank extends NetListFragment<FragmentBaseListBinding,
        ListUser, UserPreviewsBean> {

    @Override
    public RemoteRepo<ListUser> repository() {
        return new ArtistRankRepo();
    }

    @Override
    public BaseAdapter<UserPreviewsBean, RecyUserPreviewBinding> adapter() {
        return new UAdapter(allItems, mContext);
    }

    @Override
    public String getToolbarTitle() {
        return getString(R.string.artist_rank_title);
    }
}
