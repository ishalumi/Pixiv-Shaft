package ceui.lisa.fragments;

import android.os.Bundle;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.NAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyNovelBinding;
import ceui.lisa.model.ListNovel;
import ceui.lisa.models.NovelBean;
import ceui.lisa.repo.UserNovelRepo;
import ceui.lisa.utils.Params;

/**
 * 某人创作的小说
 */
public class FragmentUserNovel extends NetListFragment<FragmentBaseListBinding,
        ListNovel, NovelBean> {

    private int userID;
    // 独立页(TemplateActivity「小说作品」路由)需要自带 toolbar 提供返回箭头+标题;
    // 内嵌进 UserActivityV3 的小说 Tab 时必须关掉,否则 Tab 内容里会多出一条绿色「‹ 小说作品」头。
    private boolean showToolbar = true;

    public static FragmentUserNovel newInstance(int userID) {
        return newInstance(userID, true);
    }

    public static FragmentUserNovel newInstance(int userID, boolean paramShowToolbar) {
        Bundle args = new Bundle();
        args.putInt(Params.USER_ID, userID);
        args.putBoolean(Params.FLAG, paramShowToolbar);
        FragmentUserNovel fragment = new FragmentUserNovel();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        userID = bundle.getInt(Params.USER_ID);
        showToolbar = bundle.getBoolean(Params.FLAG, true);
    }

    @Override
    public boolean showToolbar() {
        return showToolbar;
    }

    @Override
    public RemoteRepo<ListNovel> repository() {
        return new UserNovelRepo(userID);
    }

    @Override
    public BaseAdapter<NovelBean, RecyNovelBinding> adapter() {
        return new NAdapter(allItems, mContext);
    }

    @Override
    public String getToolbarTitle() {
        return getString(R.string.string_237);
    }
}
