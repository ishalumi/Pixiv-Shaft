package ceui.lisa.adapters;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.view.View;

import com.bumptech.glide.Glide;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.UActivity;
import ceui.lisa.databinding.RecyNovelBinding;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.models.NovelBean;
import ceui.lisa.models.TagsBean;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.utils.V3Palette;
import ceui.pixiv.ui.novel.NovelSeriesFragment;
import ceui.pixiv.ui.recommend.TrendingScoreFormatKt;
import kotlin.Unit;
import kotlin.jvm.functions.Function2;

public class NAdapter extends BaseAdapter<NovelBean, RecyNovelBinding> {

    private boolean showShop = false;

    public NAdapter(List<NovelBean> targetList, Context context) {
        super(targetList, context);
        handleClick();
    }

    public NAdapter(List<NovelBean> targetList, Context context, boolean showShop) {
        super(targetList, context);
        handleClick();
        this.showShop = showShop;
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_novel;
    }

    @Override
    public void bindData(NovelBean target, ViewHolder<RecyNovelBinding> bindView, int position) {
        // 主题色 + 日夜自适应的强调色:系列 / 收藏按钮走 accent,标签流由 V3TagFlowView 自己取 palette。
        V3Palette palette = V3Palette.from(mContext);
        if (target.getSeries() != null && !TextUtils.isEmpty(target.getSeries().getTitle())) {
            bindView.baseBind.series.setVisibility(View.VISIBLE);
            bindView.baseBind.series.setTextColor(palette.getTextAccent());
            bindView.baseBind.series.setText(String.format(mContext.getString(R.string.string_184),
                    target.getSeries().getTitle()));
            if (showShop) {

            } else {
                bindView.baseBind.series.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        Intent intent = new Intent(mContext, TemplateActivity.class);
                        intent.putExtra(NovelSeriesFragment.ARG_SERIES_ID, (long) allItems.get(position).getSeries().getId());
                        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说系列");
                        mContext.startActivity(intent);
                    }
                });
            }
        } else {
            bindView.baseBind.series.setVisibility(View.GONE);
        }
        if (showShop) {
            bindView.baseBind.title.setText(String.format(Locale.getDefault(), "#%d %s", position + 1, target.getTitle()));
        } else {
            bindView.baseBind.title.setText(target.getTitle());
        }
        // Respect the user's "show tags on novel cards" setting. Feed an empty
        // list when disabled so V3TagFlowView clears its chips and collapses to
        // zero height. compact=true 让 chip 比详情页小一号;searchIndex=1 让点击
        // 跳到搜索页的「小说」tab;点击 / 长按菜单由 V3TagFlowView 内建。
        List<TagsBean> tagsToShow = Shaft.sSettings.isShowNovelCardTags()
                ? target.getTags()
                : Collections.<TagsBean>emptyList();
        bindView.baseBind.novelTag.setCompact(true);
        bindView.baseBind.novelTag.setSearchIndex(1);
        bindView.baseBind.novelTag.setJavaTags(tagsToShow);
        // 新版是竖排 LinearLayout,空标签直接 GONE 收掉上方 12dp 间距,不会像旧
        // RelativeLayout 那样丢锚点。
        bindView.baseBind.novelTag.setVisibility(tagsToShow.isEmpty() ? View.GONE : View.VISIBLE);
        // AI 生成角标(novel_ai_type == 2),与 card/v3/history/detail 同口径。
        bindView.baseBind.badgeAi.setVisibility(target.isCreatedByAI() ? View.VISIBLE : View.GONE);
        bindView.baseBind.author.setText(target.getUser().getName());
        var date = target.getCreate_date().substring(0, 10);
        bindView.baseBind.howManyWord.setText(String.format(Locale.getDefault(), "%d字 · %s", target.getText_length(), date));
        bindView.baseBind.bookmarkCount.setText(String.valueOf(target.getTotal_bookmarks()));
        // 站长推荐场景:trending repo 把 score 注入 bean。其他 fragment 复用此
        // adapter 时 trendingScore=null,bindTrendingScore 内部走 GONE,无副作用。
        TrendingScoreFormatKt.bindTrendingScore(bindView.baseBind.trendingScore, target.getTrendingScore());
        Glide.with(mContext).load(GlideUtil.getUrl(target.getImage_urls().getMaxImage())).into(bindView.baseBind.cover);
        Glide.with(mContext).load(GlideUtil.getHead(target.getUser())).into(bindView.baseBind.userHead);
        bindView.baseBind.like.setTextColor(palette.getTextAccent());
        if (target.isIs_bookmarked()) {
            bindView.baseBind.like.setText(R.string.string_169);
        } else {
            bindView.baseBind.like.setText(R.string.string_170);
        }
        if (mOnItemClickListener != null) {
            bindView.baseBind.like.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mOnItemClickListener.onItemClick(bindView.baseBind.like, position, 1);
                }
            });
            bindView.baseBind.cover.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mOnItemClickListener.onItemClick(bindView.baseBind.like, position, 2);
                }
            });
            bindView.baseBind.userHead.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mOnItemClickListener.onItemClick(bindView.baseBind.like, position, 3);
                }
            });
            bindView.baseBind.author.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mOnItemClickListener.onItemClick(bindView.baseBind.like, position, 3);
                }
            });
            bindView.baseBind.like.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(Params.ILLUST_ID, target.getId());
                    intent.putExtra(Params.DATA_TYPE, Params.TYPE_NOVEL);
                    intent.putExtra(Params.TAG_NAMES, target.getTagNames());
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "按标签收藏");
                    mContext.startActivity(intent);
                    return true;
                }
            });
            bindView.itemView.setOnClickListener(v -> mOnItemClickListener.onItemClick(v, position, 0));
        }
    }

    private void handleClick() {
        setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                // 点击监听捕获的是 bind 时的 position。下拉刷新/clear() 会清空
                // allItems,而屏幕上残留的旧 ViewHolder 仍可能用旧 position 触发点击,
                // 越界拿 allItems.get(position) 会 IndexOutOfBounds 崩。先卡边界再用。
                if (position < 0 || position >= allItems.size()) {
                    return;
                }
                if (viewType == 0) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(Params.CONTENT, allItems.get(position));
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说详情");
                    intent.putExtra("hideStatusBar", true);
                    mContext.startActivity(intent);
                } else if (viewType == 1) {
                    PixivOperate.postLikeNovel(allItems.get(position),
                            Params.TYPE_PUBLIC, v);
                } else if (viewType == 2) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(Params.URL, GlideUtil.getUrl(allItems.get(position).getImage_urls().getMaxImage()).toStringUrl());
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "图片详情");
                    mContext.startActivity(intent);
                } else if (viewType == 3) {
                    Intent intent = new Intent(mContext, UActivity.class);
                    intent.putExtra(Params.USER_ID, allItems.get(position).getUser().getId());
                    mContext.startActivity(intent);
                }
            }
        });
    }
}
