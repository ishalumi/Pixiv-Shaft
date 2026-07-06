package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;

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
import ceui.lisa.utils.V3Palette;

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

        baseBind.drawerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mActivity instanceof MainActivity) {
                    ((MainActivity) mActivity).getDrawer().openDrawer(GravityCompat.START, true);
                }
            }
        });
        baseBind.searchBar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "搜索");
                startActivity(intent);
            }
        });

        baseBind.manga.setClipToOutline(true);
        baseBind.novel.setClipToOutline(true);
        baseBind.walkThrough.setClipToOutline(true);
        baseBind.followNovels.setClipToOutline(true);
        baseBind.webStreet.setClipToOutline(true);
        // Web 首页卡片的渐变底跟随主题色(切绿/切其他色都对),并适配日夜。
        // XML 里的静态紫渐变只是占位,运行时用 V3Palette 生成的主题色渐变覆盖。
        baseBind.webStreetPlaceholder.setBackground(V3Palette.from(mContext).bannerPlaceholder());

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
        // Google Play 渠道隐藏「Web 首页」入口 —— 目前只是 "Coming soon" 占位,留着会被
        // Play Store 视作未完成功能扣分,且没有实际跳转目标。github 渠道保留,便于开发预览。
        // 这一整行(web_street_row)单独一行,Lite 直接 GONE 就行,布局自然收起,不留空白。
        if (BuildConfig.IS_LITE) {
            baseBind.webStreetRow.setVisibility(View.GONE);
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
