package ceui.lisa.fragments;

import android.os.Bundle;
import android.text.TextUtils;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.repo.UserIllustTagRepo;
import ceui.lisa.utils.Params;

/**
 * issue #569: 某画师「按 Tag 筛选」后的插画作品列表。
 * 复用 FragmentUserIllust 的瀑布流外观,数据走 {@link UserIllustTagRepo}(网页 ajax)。
 */
public class FragmentUserIllustByTag extends NetListFragment<FragmentBaseListBinding, ListIllust, IllustsBean> {

    private int userID;
    private String tag;

    public static FragmentUserIllustByTag newInstance(int userID, String tag) {
        Bundle args = new Bundle();
        args.putInt(Params.USER_ID, userID);
        args.putString(Params.KEY_WORD, tag);
        FragmentUserIllustByTag fragment = new FragmentUserIllustByTag();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        userID = bundle.getInt(Params.USER_ID);
        tag = bundle.getString(Params.KEY_WORD);
    }

    @Override
    public RemoteRepo<ListIllust> repository() {
        return new UserIllustTagRepo(userID, tag == null ? "" : tag);
    }

    @Override
    public BaseAdapter<IllustsBean, RecyIllustStaggerBinding> adapter() {
        return new IAdapter(allItems, mContext);
    }

    @Override
    public boolean showToolbar() {
        return true;
    }

    @Override
    public String getToolbarTitle() {
        return TextUtils.isEmpty(tag) ? super.getToolbarTitle() : "#" + tag;
    }

    @Override
    public void initRecyclerView() {
        staggerRecyclerView();
    }
}
