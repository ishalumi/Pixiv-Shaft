package ceui.lisa.adapters;

import android.content.Context;

import com.bumptech.glide.Glide;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.RecyRankIllustHorizontalBinding;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.GlideUtil;

public class RAdapter extends BaseAdapter<IllustsBean, RecyRankIllustHorizontalBinding> {

    public RAdapter(List<IllustsBean> targetList, Context context) {
        super(targetList, context);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_rank_illust_horizontal;
    }

    @Override
    public void bindData(IllustsBean target, ViewHolder<RecyRankIllustHorizontalBinding> bindView, int position) {
        bindView.baseBind.title.setText(allItems.get(position).getTitle());
        bindView.baseBind.author.setText(allItems.get(position).getUser().getName());
        // 排行榜 hero 卡是首页最显眼的两张大图,用 large(600x1200_90)而不是 medium(540_70),
        // centerCrop 到 180dp 卡里明显更清晰;URL 缺失时 getUrl 返 null 交给 Glide 兜底。
        Glide.with(mContext).load(GlideUtil.getUrl(allItems.get(position)
                .getImage_urls().getLarge()))
                .into(bindView.baseBind.illustImage);
        Glide.with(mContext).load(GlideUtil.getUrl(allItems.get(position)
                .getUser().getProfile_image_urls().getMedium()))
                .placeholder(R.color.light_bg).error(R.drawable.no_profile).into(bindView.baseBind.userHead);
        if (mOnItemClickListener != null) {
            bindView.itemView.setOnClickListener(v -> mOnItemClickListener.onItemClick(v, position, 0));
        }
    }
}
