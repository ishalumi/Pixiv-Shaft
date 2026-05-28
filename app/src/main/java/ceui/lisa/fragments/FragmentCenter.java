package ceui.lisa.fragments;

import android.content.Intent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.fragment.app.FragmentTransaction;

import com.scwang.smart.refresh.layout.SmartRefreshLayout;


import ceui.lisa.BuildConfig;
import ceui.lisa.R;
import ceui.lisa.activities.MainActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.databinding.FragmentNewCenterBinding;
import ceui.lisa.utils.Dev;

public class FragmentCenter extends SwipeFragment<FragmentNewCenterBinding> {

    private FragmentPivisionHorizontal pivisionFragment = null;
    
    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_new_center;
    }

    @Override
    protected void initView() {
        if (Dev.hideMainActivityStatus) {
            ViewGroup.LayoutParams headParams = baseBind.head.getLayoutParams();
            headParams.height = Shaft.statusHeight;
            baseBind.head.setLayoutParams(headParams);
        }

        baseBind.toolbar.inflateMenu(R.menu.fragment_left);
        baseBind.toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mActivity instanceof MainActivity) {
                    ((MainActivity) mActivity).getDrawer().openDrawer(GravityCompat.START, true);
                }
            }
        });
        baseBind.toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.action_search) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "搜索");
                    startActivity(intent);
                    return true;
                }
                return false;
            }
        });

        baseBind.manga.setClipToOutline(true);
        baseBind.novel.setClipToOutline(true);
        baseBind.walkThrough.setClipToOutline(true);
        baseBind.followNovels.setClipToOutline(true);
        baseBind.webStreet.setClipToOutline(true);

        baseBind.manga.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "推荐漫画");
                startActivity(intent);
            }
        });
        baseBind.novel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "推荐小说");
                intent.putExtra("hideStatusBar", false);
                startActivity(intent);
            }
        });

        baseBind.walkThrough.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "画廊");
                startActivity(intent);
            }
        });
        baseBind.followNovels.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "关注者的小说");
                startActivity(intent);
            }
        });
        // Google Play 渠道隐藏「Web 首页」入口 —— 目前只是 "Coming soon..." 占位,留着会被
        // Play Store 视作未完成功能扣分,且没有实际跳转目标。github 渠道保留,便于开发预览。
        // GridLayout 是 3 行 × 2 列固定高度 402dp,web_street 单独占第 3 行第 1 格,直接 GONE 会
        // 留 ~134dp 空白,所以把 rowCount 改成 2 同时按比例(2/3)缩小高度。
        if (BuildConfig.IS_LITE) {
            baseBind.webStreet.setVisibility(View.GONE);
            android.widget.GridLayout grid = (android.widget.GridLayout) baseBind.webStreet.getParent();
            grid.setRowCount(2);
            ViewGroup.LayoutParams gridLp = grid.getLayoutParams();
            gridLp.height = (int) (268 * getResources().getDisplayMetrics().density);
            grid.setLayoutParams(gridLp);
        } else {
            baseBind.webStreet.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    new com.qmuiteam.qmui.widget.dialog.QMUIDialog.MessageDialogBuilder(mActivity)
                            .setTitle("Web 首页")
                            .setMessage("Coming soon...")
                            .setSkinManager(com.qmuiteam.qmui.skin.QMUISkinManager.defaultInstance(mContext))
                            .addAction("OK", new com.qmuiteam.qmui.widget.dialog.QMUIDialogAction.ActionListener() {
                                @Override
                                public void onClick(com.qmuiteam.qmui.widget.dialog.QMUIDialog dialog, int index) {
                                    dialog.dismiss();
                                }
                            })
                            .show();
                }
            });
        }
    }

    @Override
    public void lazyData() {
        FragmentTransaction transaction = getChildFragmentManager().beginTransaction();

        pivisionFragment = new FragmentPivisionHorizontal();
        transaction.add(R.id.fragment_pivision, pivisionFragment, "FragmentPivisionHorizontal");
        transaction.commitNowAllowingStateLoss();
    }

    @Override
    public SmartRefreshLayout getSmartRefreshLayout() {
        return baseBind.refreshLayout;
    }

    public void forceRefresh(){
        if(pivisionFragment != null){
            pivisionFragment.forceRefresh();
        }
    }
}
