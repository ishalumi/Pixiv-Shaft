package ceui.lisa.adapters;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.view.View;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.VActivity;
import ceui.lisa.core.Container;
import ceui.lisa.core.PageData;
import ceui.lisa.databinding.RecyTagGridBinding;
import ceui.lisa.interfaces.MultiDownload;
import ceui.lisa.model.ListTrendingtag;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;

public class TagAdapter extends BaseAdapter<ListTrendingtag.TrendTagsBean, RecyTagGridBinding> implements MultiDownload {

    private static final float HEADER_RATIO = 0.66f;
    private static final float CONTENT_RATIO = 1.0f;

    public TagAdapter(List<ListTrendingtag.TrendTagsBean> targetList, Context context) {
        super(targetList, context);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_tag_grid;
    }

    @Override
    public void bindData(ListTrendingtag.TrendTagsBean target, ViewHolder<RecyTagGridBinding> bindView, int position) {
        if (position == 0) {
            bindView.baseBind.illustImage.setHeightRatio(HEADER_RATIO);
            Glide.with(mContext)
                    .load(GlideUtil.getLargeImage(allItems.get(position).getIllust()))
                    .placeholder(R.color.light_bg)
                    .into(bindView.baseBind.illustImage);
        } else {
            bindView.baseBind.illustImage.setHeightRatio(CONTENT_RATIO);
            Glide.with(mContext)
                    .load(GlideUtil.getMediumImg(allItems.get(position).getIllust()))
                    .placeholder(R.color.light_bg)
                    .into(bindView.baseBind.illustImage);
        }

        if (TextUtils.isEmpty(allItems.get(position).getTranslated_name())) {
            bindView.baseBind.chineseTitle.setText("");
        } else {
            bindView.baseBind.chineseTitle.setText(String.format("#%s", allItems.get(position).getTranslated_name()));
        }
        bindView.baseBind.title.setText(String.format("#%s", allItems.get(position).getTag()));

        // 长按 → 跳到该 tag 代表插画的详情页(单页 PageData,position 0)。
        // 之前是 startDownload(批量下) —— 跟 tap 入口都是 SearchActivity 比起来不直观,
        // 用户也容易点错;改成 VActivity 跟首页 IAdapter 长按行为对齐。
        bindView.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                IllustsBean illust = allItems.get(position).getIllust();
                if (illust == null) {
                    return false;
                }
                PageData pageData = new PageData(Collections.singletonList(illust));
                Container.get().addPageToMap(pageData);
                Intent intent = new Intent(mContext, VActivity.class);
                intent.putExtra(Params.POSITION, 0);
                intent.putExtra(Params.PAGE_UUID, pageData.getUUID());
                mContext.startActivity(intent);
                return true;
            }
        });
        if (mOnItemClickListener != null) {
            bindView.itemView.setOnClickListener(v -> mOnItemClickListener.onItemClick(v, position, 0));
        }
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public List<IllustsBean> getIllustList() {
        List<IllustsBean> tempList = new ArrayList<>();
        for (int i = 0; i < allItems.size(); i++) {
            tempList.add(allItems.get(i).getIllust());
        }
        return tempList;
    }
}
