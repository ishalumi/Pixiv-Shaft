package ceui.lisa.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import ceui.lisa.R;

/**
 * 发现页各横向货架的「加载中骨架图」adapter。渲染 [count] 张固定宽度([itemWidthPx])的骨架卡
 * (v3_surface_2 圆角块 + shimmer 微光),高度撑满 rail 的固定高。数据到达后 FragmentCenter 直接把
 * rail 的 adapter 换成真实 adapter —— rail 高度全程不变,所以页面没有任何高度跳动。
 */
public class SkeletonRailAdapter extends RecyclerView.Adapter<SkeletonRailAdapter.VH> {

    private final int count;
    private final int itemWidthPx;

    public SkeletonRailAdapter(int count, int itemWidthPx) {
        this.count = count;
        this.itemWidthPx = itemWidthPx;
    }

    static class VH extends RecyclerView.ViewHolder {
        VH(View itemView) {
            super(itemView);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.recy_skeleton_card, parent, false);
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        lp.width = itemWidthPx; // 高度用 XML 的 match_parent 撑满固定高的 rail
        v.setLayoutParams(lp);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        // 纯占位,无数据绑定
    }

    @Override
    public int getItemCount() {
        return count;
    }
}
