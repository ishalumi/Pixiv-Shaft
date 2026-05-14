package ceui.lisa.fragments;

import android.Manifest;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.os.Bundle;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.databinding.DataBindingUtil;

import java.io.File;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.blankj.utilcode.util.FileUtils;
import com.blankj.utilcode.util.UriUtils;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;
import com.scwang.smart.refresh.header.FalsifyFooter;
import com.scwang.smart.refresh.header.FalsifyHeader;
import com.scwang.smart.refresh.layout.SmartRefreshLayout;

import com.tbruyelle.rxpermissions3.RxPermissions;

import java.util.Arrays;
import java.util.Locale;

import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.core.Manager;
import ceui.lisa.databinding.FragmentSettingsBinding;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.file.LegacyFile;
import ceui.lisa.helper.NavigationLocationHelper;
import ceui.lisa.helper.PageTransformerHelper;
import ceui.lisa.helper.ThemeHelper;
import ceui.lisa.http.HttpDns;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.utils.BackupUtils;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DownloadLimitTypeUtil;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivSearchParamUtil;
import ceui.lisa.utils.Settings;
import ceui.loxia.Client;
import ceui.loxia.MoonSync;
import ceui.pixiv.download.DownloadsRegistry;
import ceui.pixiv.download.config.OverwritePolicy;
import ceui.pixiv.download.config.StorageChoice;
import ceui.pixiv.session.SessionManager;

import static android.app.Activity.RESULT_OK;
import static android.provider.DocumentsContract.EXTRA_INITIAL_URI;
import static ceui.lisa.helper.ThemeHelper.ThemeType.DARK_MODE;
import static ceui.lisa.helper.ThemeHelper.ThemeType.DEFAULT_MODE;
import static ceui.lisa.helper.ThemeHelper.ThemeType.LIGHT_MODE;


public class FragmentSettings extends SwipeFragment<FragmentSettingsBinding> {

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_settings;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        if (rootView == null) {
            View prewarmed = ceui.lisa.utils.LayoutPrewarmer.consume(R.layout.fragment_settings);
            if (prewarmed != null) {
                FragmentSettingsBinding bound = DataBindingUtil.bind(prewarmed);
                if (bound != null) {
                    initLayout();
                    baseBind = bound;
                    rootView = prewarmed;
                    isInit = true;
                    initView();
                    initData();
                    return rootView;
                }
            }
        }
        return super.onCreateView(inflater, container, savedInstanceState);
    }

    @Override
    protected void initData() {
        baseBind.toolbar.setNavigationOnClickListener(view -> mActivity.finish());

        // 账号
        {
            baseBind.userManage.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "账号管理");
                    startActivity(intent);
                }
            });

            baseBind.editAccount.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "绑定邮箱");
                    startActivity(intent);
                }
            });

            baseBind.editFile.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "编辑个人资料");
                    startActivity(intent);
                }
            });

            baseBind.workSpace.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "我的作业环境");
                    startActivity(intent);
                }
            });

            baseBind.r18Space.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接");
                    intent.putExtra(Params.URL, Params.URL_R18_SETTING);
                    startActivity(intent);
                }
            });

            baseBind.premiumSpace.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接");
                    intent.putExtra(Params.URL, Params.URL_PREMIUM_SETTING);
                    startActivity(intent);
                }
            });

            baseBind.loginOut.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    QMUIDialog.CheckBoxMessageDialogBuilder builder = new QMUIDialog.CheckBoxMessageDialogBuilder(getActivity());
                    builder
                            .setTitle(getString(R.string.string_185))
                            .setMessage(getString(R.string.string_186))
                            .setChecked(true)
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addAction(getString(R.string.string_187), new QMUIDialogAction.ActionListener() {
                                @Override
                                public void onClick(QMUIDialog dialog, int index) {
                                    dialog.dismiss();
                                }
                            })
                            .addAction(R.string.login_out, new QMUIDialogAction.ActionListener() {
                                @Override
                                public void onClick(QMUIDialog dialog, int index) {
                                    Common.logOut(mContext, builder.isChecked());
                                    mActivity.finish();
                                    dialog.dismiss();
                                }
                            })
                            .create()
                            .show();
                }
            });
        }

        // 网络
        {
            baseBind.autoDns.setChecked(Shaft.sSettings.isDirectConnect());
            // DoH 只在直连开启时生效，跟随直连开关显隐
            baseBind.useSecureDnsGroup.setVisibility(
                    Shaft.sSettings.isDirectConnect() ? View.VISIBLE : View.GONE);
            baseBind.autoDns.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    boolean changed = isChecked != Shaft.sSettings.isDirectConnect();
                    Shaft.sSettings.setDirectConnect(isChecked);
                    Common.showToast(getString(R.string.string_428), 2);
                    Local.setSettings(Shaft.sSettings);
                    ViewGroup secureDnsParent = (ViewGroup) baseBind.useSecureDnsGroup.getParent();
                    if (secureDnsParent != null) {
                        TransitionManager.beginDelayedTransition(secureDnsParent, new AutoTransition());
                    }
                    baseBind.useSecureDnsGroup.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                    if (changed) {
                        Retro.refreshAppApi();
                        Client.INSTANCE.reset();
                    }
                }
            });
            baseBind.directConnectLink.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接");
                    intent.putExtra(Params.URL, "https://github.com/Notsfsssf/Pix-EzViewer");
                    intent.putExtra(Params.TITLE, "PxEz项目主页");
                    startActivity(intent);
                }
            });
            baseBind.directConnectRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.autoDns.performClick();
                }
            });

            //安全 DNS（DoH） issue #616
            baseBind.useSecureDns.setChecked(Shaft.sSettings.isUseSecureDns());
            baseBind.useSecureDns.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setUseSecureDns(isChecked);
                    Common.showToast(getString(R.string.string_428), 2);
                    Local.setSettings(Shaft.sSettings);
                    HttpDns.invalidate();
                }
            });
            baseBind.useSecureDnsRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.useSecureDns.performClick();
                }
            });

            //缩略图是否显示大图
            baseBind.showLargeThumbnailImage.setChecked(Shaft.sSettings.isShowLargeThumbnailImage());
            baseBind.showLargeThumbnailImage.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setShowLargeThumbnailImage(isChecked);
                    Common.showToast(getString(R.string.string_428));
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.showLargeThumbnailImageRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.showLargeThumbnailImage.performClick();
                }
            });

            //详情是否显示原图
            baseBind.showOriginalPreviewImage.setChecked(Shaft.sSettings.isShowOriginalPreviewImage());
            baseBind.showOriginalPreviewImage.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setShowOriginalPreviewImage(isChecked);
                    Common.showToast(getString(R.string.string_428));
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.showOriginalPreviewImageRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.showOriginalPreviewImage.performClick();
                }
            });
        }

        // 常规
        {
            baseBind.saveHistory.setChecked(Shaft.sSettings.isSaveViewHistory());
            baseBind.saveHistory.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setSaveViewHistory(isChecked);
                    Common.showToast(getString(R.string.string_428), 2);
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.saveHistoryRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.saveHistory.performClick();
                }
            });

            baseBind.deleteStarIllust.setChecked(Shaft.sSettings.isDeleteStarIllust());
            baseBind.deleteStarIllust.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setDeleteStarIllust(isChecked);
                    Common.showToast(getString(R.string.string_428), 2);
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.deleteStarIllustRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.deleteStarIllust.performClick();
                }
            });

            baseBind.filterRankBookmarked.setChecked(Shaft.sSettings.isFilterRankBookmarked());
            baseBind.filterRankBookmarked.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setFilterRankBookmarked(isChecked);
                    Common.showToast(getString(R.string.string_428), 2);
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.filterRankBookmarkedRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.filterRankBookmarked.performClick();
                }
            });

            baseBind.filterInvalidBookmarks.setChecked(Shaft.sSettings.isFilterInvalidBookmarks());
            baseBind.filterInvalidBookmarks.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setFilterInvalidBookmarks(isChecked);
                    Common.showToast(getString(R.string.string_428), 2);
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.filterInvalidBookmarksRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.filterInvalidBookmarks.performClick();
                }
            });

            baseBind.deleteAiIllust.setChecked(Shaft.sSettings.isDeleteAIIllust());
            baseBind.deleteAiIllust.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setDeleteAIIllust(isChecked);
                    Common.showToast(getString(R.string.string_428), 2);
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.deleteAiIllustRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.deleteAiIllust.performClick();
                }
            });

            baseBind.toastDownloadResult.setChecked(Shaft.sSettings.isToastDownloadResult());
            baseBind.toastDownloadResult.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setToastDownloadResult(isChecked);
                    Common.showToast(getString(R.string.string_428), 2);
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.toastDownloadResultRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.toastDownloadResult.performClick();
                }
            });

            final String searchFilter = Shaft.sSettings.getSearchFilter();
            baseBind.searchFilter.setText(PixivSearchParamUtil.getSizeName(searchFilter));
            baseBind.searchFilterRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    new QMUIDialog.CheckableDialogBuilder(mContext)
                            .setCheckedIndex(PixivSearchParamUtil.getSizeIndex(Shaft.sSettings.getSearchFilter()))
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addItems(PixivSearchParamUtil.ALL_SIZE_NAME, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    Shaft.sSettings.setSearchFilter(PixivSearchParamUtil.ALL_SIZE_VALUE[which]);
                                    Common.showToast(getString(R.string.string_428), 2);
                                    Local.setSettings(Shaft.sSettings);
                                    baseBind.searchFilter.setText(PixivSearchParamUtil.ALL_SIZE_NAME[which]);
                                    dialog.dismiss();
                                }
                            })
                            .create()
                            .show();
                }
            });

            // 搜索结果默认排序方式
            final String searchDefaultSortType = Shaft.sSettings.getSearchDefaultSortType();
            baseBind.searchDefaultSortType.setText(PixivSearchParamUtil.getSortTypeName(searchDefaultSortType));
            baseBind.searchDefaultSortTypeRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    new QMUIDialog.CheckableDialogBuilder(mContext)
                            .setCheckedIndex(PixivSearchParamUtil.getSortTypeIndex(Shaft.sSettings.getSearchDefaultSortType()))
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addItems(PixivSearchParamUtil.SORT_TYPE_NAME, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    Shaft.sSettings.setSearchDefaultSortType(PixivSearchParamUtil.SORT_TYPE_VALUE[which]);
                                    Common.showToast(getString(R.string.string_428), 2);
                                    Local.setSettings(Shaft.sSettings);
                                    baseBind.searchDefaultSortType.setText(PixivSearchParamUtil.SORT_TYPE_NAME[which]);
                                    dialog.dismiss();
                                }
                            })
                            .create()
                            .show();
                }
            });

            // 过滤垃圾评论
            baseBind.filterComment.setChecked(Shaft.sSettings.isFilterComment());
            baseBind.filterComment.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setFilterComment(isChecked);
                    Common.showToast(getString(R.string.string_428), 2);
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.filterCommentRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.filterComment.performClick();
                }
            });

            // 默认开启R18内容过滤
            baseBind.r18FilterDefaultEnable.setChecked(Shaft.sSettings.isR18FilterDefaultEnable());
            baseBind.r18FilterDefaultEnable.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setR18FilterDefaultEnable(isChecked);
                    Common.showToast(getString(R.string.string_428), 2);
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.r18FilterDefaultEnableRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.r18FilterDefaultEnable.performClick();
                }
            });
        }

        // 界面
        {
            // APP主页显示R页面
            baseBind.mainViewR18.setChecked(Shaft.sSettings.isMainViewR18());
            baseBind.mainViewR18.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setMainViewR18(isChecked);
                    Common.showToast(getString(R.string.please_restart_app), 2);
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.mainViewR18Rela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.mainViewR18.performClick();
                }
            });

            // 首页导航栏初始化位置
            String navigationInitPositionSettingValue = Shaft.sSettings.getNavigationInitPosition();
            final String navigationInitPosition = !TextUtils.isEmpty(navigationInitPositionSettingValue) ? navigationInitPositionSettingValue : NavigationLocationHelper.TUIJIAN;
            baseBind.navigationInitPosition.setText(NavigationLocationHelper.SETTING_NAME_MAP.get(navigationInitPosition));
            baseBind.navigationInitPositionRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String[] OPTION_VALUES = NavigationLocationHelper.SETTING_NAME_MAP.keySet().toArray(new String[0]);
                    String[] OPTION_NAMES = NavigationLocationHelper.SETTING_NAME_MAP.values().toArray(new String[0]);
                    String navigationInitPositionSettingValue = Shaft.sSettings.getNavigationInitPosition();
                    final String navigationInitPosition = !TextUtils.isEmpty(navigationInitPositionSettingValue) ? navigationInitPositionSettingValue : NavigationLocationHelper.TUIJIAN;
                    final int index = Arrays.asList(OPTION_VALUES).indexOf(navigationInitPosition);
                    new QMUIDialog.CheckableDialogBuilder(mActivity)
                            .setCheckedIndex(index)
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addItems(OPTION_NAMES, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    if (which != index) {
                                        Shaft.sSettings.setNavigationInitPosition(OPTION_VALUES[which]);
                                        baseBind.navigationInitPosition.setText(OPTION_NAMES[which]);
                                        Local.setSettings(Shaft.sSettings);
                                    }
                                    dialog.dismiss();
                                }
                            })
                            .show();
                }
            });

            // 新版作品详情
            baseBind.illustDetailUserNew.setChecked(Shaft.sSettings.isUseFragmentIllust());
            baseBind.illustDetailUserNew.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setUseFragmentIllust(isChecked);
                    Common.showToast(getString(R.string.string_428), 2);
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.illustDetailUserNewRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.illustDetailUserNew.performClick();
                }
            });

            // V3沉浸式作品详情
            baseBind.illustDetailV3.setChecked(Shaft.sSettings.isUseArtworkV3());
            applyArtworkV3FabOrderRowVisibility(Shaft.sSettings.isUseArtworkV3(), false);
            baseBind.illustDetailV3.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setUseArtworkV3(isChecked);
                    Common.showToast(getString(R.string.string_428), 2);
                    Local.setSettings(Shaft.sSettings);
                    applyArtworkV3FabOrderRowVisibility(isChecked, true);
                }
            });
            baseBind.illustDetailV3Rela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.illustDetailV3.performClick();
                }
            });

            // V3详情页 下载/收藏按钮顺序
            updateArtworkV3FabOrderLabel();
            baseBind.artworkV3FabOrderSelect.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final int index = Shaft.sSettings.isArtworkV3FabDownloadOnLeft() ? 0 : 1;
                    String[] items = new String[]{
                            getString(R.string.artwork_v3_fab_order_download_left),
                            getString(R.string.artwork_v3_fab_order_bookmark_left),
                    };
                    new QMUIDialog.CheckableDialogBuilder(mActivity)
                            .setCheckedIndex(index)
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addItems(items, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    if (which != index) {
                                        Shaft.sSettings.setArtworkV3FabDownloadOnLeft(which == 0);
                                        Local.setSettings(Shaft.sSettings);
                                        updateArtworkV3FabOrderLabel();
                                    }
                                    dialog.dismiss();
                                }
                            })
                            .show();
                }
            });
            baseBind.artworkV3FabOrderRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.artworkV3FabOrderSelect.performClick();
                }
            });

            // 主题模式
            baseBind.themeMode.setText(Shaft.sSettings.getThemeType().toDisplayString(mContext));
            baseBind.themeModeRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final int index = Shaft.sSettings.getThemeType().themeTypeIndex;
                    ThemeHelper.ThemeType[] THEME_MODES = new ThemeHelper.ThemeType[]{
                            DEFAULT_MODE,
                            LIGHT_MODE,
                            DARK_MODE
                    };
                    String[] THEME_NAME = new String[]{
                            THEME_MODES[0].toDisplayString(mContext),
                            THEME_MODES[1].toDisplayString(mContext),
                            THEME_MODES[2].toDisplayString(mContext)
                    };
                    new QMUIDialog.CheckableDialogBuilder(mActivity)
                            .setCheckedIndex(index)
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addItems(THEME_NAME, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    if (which != index) {
                                        Shaft.sSettings.setThemeType(((AppCompatActivity) mActivity), THEME_MODES[which]);
                                        baseBind.themeMode.setText(THEME_NAME[which]);
                                        Local.setSettings(Shaft.sSettings);
                                    }
                                    dialog.dismiss();
                                }
                            })
                            .show();
                }
            });

            // 主题色彩
            setThemeName();
            baseBind.colorSelectRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "主题颜色");
                    startActivity(intent);
                }
            });

            baseBind.layoutMode.setText(Shaft.sSettings.isUseStaggeredLayout()
                    ? getString(R.string.layout_staggered) : getString(R.string.layout_linear));
            baseBind.layoutModeRela.setOnClickListener(v -> {
                String[] options = new String[]{
                        getString(R.string.layout_staggered),
                        getString(R.string.layout_linear)
                };
                int currentIndex = Shaft.sSettings.isUseStaggeredLayout() ? 0 : 1;
                new QMUIDialog.CheckableDialogBuilder(mActivity)
                        .setCheckedIndex(currentIndex)
                        .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                        .addItems(options, (dialog, which) -> {
                            if (which != currentIndex) {
                                Shaft.sSettings.setUseStaggeredLayout(which == 0);
                                baseBind.layoutMode.setText(options[which]);
                                Local.setSettings(Shaft.sSettings);
                            }
                            dialog.dismiss();
                        })
                        .show();
            });

            baseBind.lineCount.setText(getString(R.string.string_349, Shaft.sSettings.getLineCount()));
            baseBind.lineCountRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int index = 0;
                    if (Shaft.sSettings.getLineCount() == 3) {
                        index = 1;
                    } else if (Shaft.sSettings.getLineCount() == 4) {
                        index = 2;
                    }
                    String[] LINE_COUNT = new String[]{
                            getString(R.string.string_349, 2),
                            getString(R.string.string_349, 3),
                            getString(R.string.string_349, 4)
                    };
                    final int selectIndex = index;
                    new QMUIDialog.CheckableDialogBuilder(mActivity)
                            .setCheckedIndex(selectIndex)
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addItems(LINE_COUNT, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    if (which != selectIndex) {
                                        int lineCount = which + 2;
                                        Shaft.sSettings.setLineCount(lineCount);
                                        baseBind.lineCount.setText(getString(R.string.string_349, lineCount));
                                        Local.setSettings(Shaft.sSettings);
                                        Common.showToast(getString(R.string.please_restart_app), 2);
                                    }
                                    dialog.dismiss();
                                }
                            })
                            .show();
                }
            });

            // 首页底部页签顺序
            setOrderName();
            baseBind.orderSelect.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final int index = Shaft.sSettings.getBottomBarOrder();
                    String[] ORDER_NAME = new String[]{
                            getString(R.string.string_343),
                            getString(R.string.string_344),
                            getString(R.string.string_345),
                            getString(R.string.string_346),
                            getString(R.string.string_347),
                            getString(R.string.string_348),
                    };
                    new QMUIDialog.CheckableDialogBuilder(mActivity)
                            .setCheckedIndex(index)
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addItems(ORDER_NAME, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    if (which == index) {
                                        Common.showLog("什么也不做");
                                    } else {
                                        Shaft.sSettings.setBottomBarOrder(which);
                                        baseBind.orderSelect.setText(ORDER_NAME[which]);
                                        Local.setSettings(Shaft.sSettings);
                                        Common.showToast(getString(R.string.please_restart_app));
                                    }
                                    dialog.dismiss();
                                }
                            })
                            .show();
                }
            });
            baseBind.bottomBarOrderRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.orderSelect.performClick();
                }
            });

            // 语言
            baseBind.appLanguage.setText(currentLanguageDisplay());
            baseBind.appLanguageRela.setOnClickListener(v -> {
                // "跟随系统" 放在首位
                java.util.List<String> labels = new java.util.ArrayList<>();
                java.util.List<String> tags = new java.util.ArrayList<>();
                labels.add(getString(R.string.language_follow_system));
                tags.add(null);
                for (String tag : ceui.pixiv.i18n.AppLocales.INSTANCE.getSupportedTags()) {
                    labels.add(ceui.pixiv.i18n.AppLocales.INSTANCE.displayName(tag));
                    tags.add(tag);
                }
                int checkedIndex = 0; // default: follow system
                if (!ceui.pixiv.i18n.AppLocales.INSTANCE.isFollowingSystem()) {
                    String currentTag = ceui.pixiv.i18n.AppLocales.INSTANCE.currentLocale().toLanguageTag();
                    int idx = tags.indexOf(currentTag);
                    if (idx >= 0) checkedIndex = idx;
                }
                new QMUIDialog.CheckableDialogBuilder(getActivity())
                        .setCheckedIndex(checkedIndex)
                        .addItems(labels.toArray(new String[0]), (dialog, which) -> {
                            String tag = tags.get(which);
                            ceui.pixiv.i18n.AppLocales.INSTANCE.apply(tag);
                            baseBind.appLanguage.setText(labels.get(which));
                            Common.showToast(getString(R.string.string_428), 2);
                            dialog.dismiss();
                        })
                        .show();
            });
        }

        // 下载
        {
            // R18 / AI 分目录开关：layout 里仍然存在，但路径 / 命名已收到「下载路径 / 文件名」页，
            // 这两个 switch 在主设置页保持隐藏，避免和 v3 配置打架。
            baseBind.r18DivideSaveRela.setVisibility(View.GONE);
            baseBind.aiDivideSaveRela.setVisibility(View.GONE);

            baseBind.r18DivideSave.setChecked(Shaft.sSettings.isR18DivideSave());
            baseBind.r18DivideSave.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setR18DivideSave(isChecked);
                    Common.showToast(getString(R.string.string_428));
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.r18DivideSaveRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.r18DivideSave.performClick();
                }
            });

            // AI作品下载至单独的目录
            baseBind.aiDivideSave.setChecked(Shaft.sSettings.isAIDivideSave());
            baseBind.aiDivideSave.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setAIDivideSave(isChecked);
                    Common.showToast(getString(R.string.string_428));
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.aiDivideSaveRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.aiDivideSave.performClick();
                }
            });

            // 下载路径 / 文件名 —— 所有分目录 / 命名 / 存储位置的配置都收在这一个入口
            baseBind.fileNameS.setText(getString(R.string.download_path_title));
            baseBind.fileName.setText(getString(R.string.download_path_entry_desc));
            baseBind.fileNameRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "下载路径与文件名");
                    startActivity(intent);
                }
            });

            // 下载内容信息头 —— 可视化勾选 / 拖拽排序小说 TXT 的元信息块
            baseBind.novelHeaderRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说信息头");
                    startActivity(intent);
                }
            });

            // 默认小说下载格式
            final String[] NOVEL_FORMAT_NAMES = new String[]{
                    getString(R.string.option_always_ask),
                    getString(R.string.format_txt),
                    getString(R.string.format_markdown),
                    getString(R.string.format_epub),
                    getString(R.string.format_pdf)
            };
            final String[] NOVEL_FORMAT_VALUES = new String[]{"", "Txt", "Markdown", "Epub", "Pdf"};
            {
                int idx = 0;
                String cur = Shaft.sSettings.getDefaultNovelExportFormat();
                for (int i = 0; i < NOVEL_FORMAT_VALUES.length; i++) {
                    if (NOVEL_FORMAT_VALUES[i].equals(cur)) { idx = i; break; }
                }
                baseBind.defaultNovelFormat.setText(NOVEL_FORMAT_NAMES[idx]);
            }
            baseBind.defaultNovelFormatRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int checkedIdx = 0;
                    String cur = Shaft.sSettings.getDefaultNovelExportFormat();
                    for (int i = 0; i < NOVEL_FORMAT_VALUES.length; i++) {
                        if (NOVEL_FORMAT_VALUES[i].equals(cur)) { checkedIdx = i; break; }
                    }
                    new QMUIDialog.CheckableDialogBuilder(mActivity)
                            .setCheckedIndex(checkedIdx)
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addItems(NOVEL_FORMAT_NAMES, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    Shaft.sSettings.setDefaultNovelExportFormat(NOVEL_FORMAT_VALUES[which]);
                                    baseBind.defaultNovelFormat.setText(NOVEL_FORMAT_NAMES[which]);
                                    Local.setSettings(Shaft.sSettings);
                                    dialog.dismiss();
                                }
                            })
                            .show();
                }
            });

            // 默认图片保存清晰度
            final String[] IMG_RES_NAMES = new String[]{
                    getString(R.string.resolution_original),
                    getString(R.string.resolution_large),
                    getString(R.string.resolution_medium),
                    getString(R.string.resolution_square_medium)
            };
            final String[] IMG_RES_VALUES = new String[]{
                    Params.IMAGE_RESOLUTION_ORIGINAL,
                    Params.IMAGE_RESOLUTION_LARGE,
                    Params.IMAGE_RESOLUTION_MEDIUM,
                    Params.IMAGE_RESOLUTION_SQUARE_MEDIUM
            };
            {
                int idx = 0;
                String cur = Shaft.sSettings.getDefaultImageResolution();
                if (!cur.isEmpty()) {
                    for (int i = 0; i < IMG_RES_VALUES.length; i++) {
                        if (IMG_RES_VALUES[i].equals(cur)) { idx = i; break; }
                    }
                }
                baseBind.defaultImageResolution.setText(IMG_RES_NAMES[idx]);
            }
            baseBind.defaultImageResolutionRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int checkedIdx = 0;
                    String cur = Shaft.sSettings.getDefaultImageResolution();
                    if (!cur.isEmpty()) {
                        for (int i = 0; i < IMG_RES_VALUES.length; i++) {
                            if (IMG_RES_VALUES[i].equals(cur)) { checkedIdx = i; break; }
                        }
                    }
                    new QMUIDialog.CheckableDialogBuilder(mActivity)
                            .setCheckedIndex(checkedIdx)
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addItems(IMG_RES_NAMES, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    Shaft.sSettings.setDefaultImageResolution(IMG_RES_VALUES[which]);
                                    baseBind.defaultImageResolution.setText(IMG_RES_NAMES[which]);
                                    Local.setSettings(Shaft.sSettings);
                                    dialog.dismiss();
                                }
                            })
                            .show();
                }
            });

            // 文件重复时（OverwritePolicy）
            final String[] POLICY_NAMES = new String[]{
                    getString(R.string.download_path_policy_skip),
                    getString(R.string.download_path_policy_replace),
                    getString(R.string.download_path_policy_rename)
            };
            final OverwritePolicy[] POLICY_VALUES = OverwritePolicy.values();
            {
                OverwritePolicy cur = DownloadsRegistry.getStore().loadOrFallback().getDefaults().getOverwrite();
                baseBind.overwritePolicy.setText(POLICY_NAMES[cur.ordinal()]);
            }
            baseBind.overwritePolicyRela.setOnClickListener(v -> {
                OverwritePolicy cur = DownloadsRegistry.getStore().loadOrFallback().getDefaults().getOverwrite();
                new QMUIDialog.CheckableDialogBuilder(mActivity)
                        .setCheckedIndex(cur.ordinal())
                        .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                        .addItems(POLICY_NAMES, (dialog, which) -> {
                            OverwritePolicy selected = POLICY_VALUES[which];
                            DownloadsRegistry.getStore().update(cfg ->
                                    cfg.copy(
                                            cfg.getVersion(),
                                            cfg.getDefaults().copy(
                                                    cfg.getDefaults().getTemplate(),
                                                    cfg.getDefaults().getStorage(),
                                                    selected
                                            ),
                                            cfg.getPerBucket(),
                                            cfg.getWifiOnly(),
                                            cfg.getPageIndexFrom1()
                                    )
                            );
                            baseBind.overwritePolicy.setText(POLICY_NAMES[which]);
                            dialog.dismiss();
                        })
                        .show();
            });

            // 存储位置（StorageChoice）—— 0 = Pictures, 1 = Downloads, 2 = SAF
            refreshStorageLabel();
            baseBind.storageChoiceRela.setOnClickListener(v -> {
                new QMUIDialog.CheckableDialogBuilder(mActivity)
                        .setCheckedIndex(currentStorageIndex())
                        .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                        .addItems(storageNames(), (dialog, which) -> {
                            dialog.dismiss();
                            if (which == 0) {
                                DownloadsRegistry.applyGlobalStorage(
                                        new StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Images));
                                refreshStorageLabel();
                            } else if (which == 1) {
                                DownloadsRegistry.applyGlobalStorage(
                                        new StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Downloads));
                                refreshStorageLabel();
                            } else {
                                // SAF：走 BaseActivity.launchSafTreePicker。
                                // 1) 必须 legacy Activity.startActivityForResult —— 之前用 AndroidX 的
                                //    registerForActivityResult,vivo/iQOO 上 picker 选完投递不回回调。
                                // 2) 必须 explicit setComponent —— vivo OriginOS 非 debuggable apk 会拦掉
                                //    implicit ACTION_OPEN_DOCUMENT_TREE 重定向到 LAUNCHER。详见 helper 注释。
                                // BaseActivity#onActivityResult 的 ASK_URI 分支负责落 URI、拿 persistable
                                // 权限、applyGlobalStorage、toast;这边的 label 在 onResume 里再刷一次。
                                BaseActivity.launchSafTreePicker(mActivity);
                            }
                        })
                        .show();
            });

            // 「设置 SAF 目录」直拉入口 —— 不经 QMUIDialog,点了立刻 startActivityForResult。
            // 这是为 vivo OriginOS 等 ROM 留的逃生口:在那些机器上,QMUIDialog 的 dismiss 动画
            // 会和系统 picker 的 intent 投递抢 window focus,有概率 picker 起不来或选完不回调。
            // 这条入口完全照搬 demo 的最朴素调用,跟 storageChoiceRela 那条平行,
            // 用户哪条能用走哪条;两条最终都汇到 BaseActivity#onActivityResult(ASK_URI) 落库。
            refreshSafDirectPickLabel();
            baseBind.safDirectPickRela.setOnClickListener(v -> {
                BaseActivity.launchSafTreePicker(mActivity);
            });

            // 多图页码起始（pageIndexFrom1）
            final String[] PAGE_INDEX_NAMES = new String[]{
                    getString(R.string.setting_page_index_from_0),
                    getString(R.string.setting_page_index_from_1)
            };
            {
                boolean from1 = DownloadsRegistry.getStore().loadOrFallback().getPageIndexFrom1();
                baseBind.pageIndex.setText(PAGE_INDEX_NAMES[from1 ? 1 : 0]);
            }
            baseBind.pageIndexRela.setOnClickListener(v -> {
                boolean from1 = DownloadsRegistry.getStore().loadOrFallback().getPageIndexFrom1();
                new QMUIDialog.CheckableDialogBuilder(mActivity)
                        .setCheckedIndex(from1 ? 1 : 0)
                        .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                        .addItems(PAGE_INDEX_NAMES, (dialog, which) -> {
                            boolean selected = which == 1;
                            DownloadsRegistry.getStore().update(cfg ->
                                    cfg.copy(
                                            cfg.getVersion(),
                                            cfg.getDefaults(),
                                            cfg.getPerBucket(),
                                            cfg.getWifiOnly(),
                                            selected
                                    )
                            );
                            baseBind.pageIndex.setText(PAGE_INDEX_NAMES[which]);
                            dialog.dismiss();
                        })
                        .show();
            });

            //插画详情长按下载
            baseBind.illustLongPressDownload.setChecked(Shaft.sSettings.isIllustLongPressDownload());
            baseBind.illustLongPressDownload.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setIllustLongPressDownload(isChecked);
                    Common.showToast(getString(R.string.please_restart_app));
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.illustLongPressDownloadRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.illustLongPressDownload.performClick();
                }
            });

            //下载限制类型
            final String[] DOWNLOAD_START_TYPE_NAMES = new String[]{
                    getString(DownloadLimitTypeUtil.DOWNLOAD_START_TYPE_IDS[0]),
                    getString(DownloadLimitTypeUtil.DOWNLOAD_START_TYPE_IDS[1]),
                    getString(DownloadLimitTypeUtil.DOWNLOAD_START_TYPE_IDS[2])
            };
            baseBind.downloadLimitType.setText(DOWNLOAD_START_TYPE_NAMES[DownloadLimitTypeUtil.getCurrentStatusIndex()]);
            baseBind.downloadLimitType.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    new QMUIDialog.CheckableDialogBuilder(mActivity)
                            .setCheckedIndex(Shaft.sSettings.getDownloadLimitType())
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addItems(DOWNLOAD_START_TYPE_NAMES, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    if (which == Shaft.sSettings.getDownloadLimitType()) {
                                        Common.showLog("什么也不做");
                                    } else {
                                        Shaft.sSettings.setDownloadLimitType(which);
                                        baseBind.downloadLimitType.setText(DOWNLOAD_START_TYPE_NAMES[DownloadLimitTypeUtil.getCurrentStatusIndex()]);
                                        Common.showToast(getString(R.string.string_428));
                                        Local.setSettings(Shaft.sSettings);
                                    }
                                    dialog.dismiss();
                                }
                            })
                            .show();
                }
            });
            baseBind.downloadLimitTypeRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.downloadLimitType.performClick();
                }
            });

            // 同时下载数 1-5（issue #859：用户希望多任务下载，默认 1=旧串行行为）
            final String[] CONCURRENCY_NAMES = new String[]{
                    getString(R.string.setting_max_concurrent_downloads_one),
                    getString(R.string.setting_max_concurrent_downloads_n, 2),
                    getString(R.string.setting_max_concurrent_downloads_n, 3),
                    getString(R.string.setting_max_concurrent_downloads_n, 4),
                    getString(R.string.setting_max_concurrent_downloads_n, 5),
            };
            final Runnable refreshConcurrencyLabel = () -> {
                int n = Shaft.sSettings.getMaxConcurrentDownloads();
                if (n < 1) n = 1; if (n > 5) n = 5;
                baseBind.maxConcurrentDownloads.setText(CONCURRENCY_NAMES[n - 1]);
            };
            refreshConcurrencyLabel.run();
            baseBind.maxConcurrentDownloads.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int current = Shaft.sSettings.getMaxConcurrentDownloads();
                    if (current < 1) current = 1; if (current > 5) current = 5;
                    new QMUIDialog.CheckableDialogBuilder(mActivity)
                            .setCheckedIndex(current - 1)
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addItems(CONCURRENCY_NAMES, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    int chosen = which + 1;
                                    if (chosen != Shaft.sSettings.getMaxConcurrentDownloads()) {
                                        Shaft.sSettings.setMaxConcurrentDownloads(chosen);
                                        Local.setSettings(Shaft.sSettings);
                                        refreshConcurrencyLabel.run();
                                        Common.showToast(getString(R.string.setting_max_concurrent_downloads_changed, chosen));
                                        // 即时生效：仅 pump 新增的槽位，不要 startAll —— 那会把
                                        // 用户手动暂停的 item 一并恢复，违反用户预期。
                                        try { Manager.get().pumpAvailableSlots(); } catch (Exception ignored) {}
                                    }
                                    dialog.dismiss();
                                }
                            })
                            .show();
                }
            });
            baseBind.maxConcurrentDownloadsRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.maxConcurrentDownloads.performClick();
                }
            });
        }

        // 个性化
        {
            baseBind.showLikeButton.setChecked(Shaft.sSettings.isPrivateStar());
            baseBind.showLikeButton.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setPrivateStar(isChecked);
                    Common.showToast(getString(R.string.string_428), 2);
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.showLikeButtonRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.showLikeButton.performClick();
                }
            });

            baseBind.showNovelCardTags.setChecked(Shaft.sSettings.isShowNovelCardTags());
            baseBind.showNovelCardTags.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setShowNovelCardTags(isChecked);
                    Common.showToast(getString(R.string.string_428));
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.showNovelCardTagsRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.showNovelCardTags.performClick();
                }
            });

            baseBind.hideStarBar.setChecked(Shaft.sSettings.isHideStarButtonAtMyCollection());
            baseBind.hideStarBar.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setHideStarButtonAtMyCollection(isChecked);
                    Common.showToast(getString(R.string.string_428));
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.hideStarBarRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.hideStarBar.performClick();
                }
            });

            baseBind.selectAllTag.setChecked(Shaft.sSettings.isStarWithTagSelectAll());
            baseBind.selectAllTag.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setStarWithTagSelectAll(isChecked);
                    Common.showToast(getString(R.string.string_428));
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.selectAllTagRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.selectAllTag.performClick();
                }
            });

            String[] transformerNames = PageTransformerHelper.getTransformerNames();
            baseBind.transformType.setText(transformerNames[PageTransformerHelper.getCurrentTransformerIndex()]);
            baseBind.transformTypeRela.setOnClickListener(new View.OnClickListener(){
                @Override
                public void onClick(View v) {
                    new QMUIDialog.CheckableDialogBuilder(mActivity)
                            .setCheckedIndex(PageTransformerHelper.getCurrentTransformerIndex())
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addItems(transformerNames, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    if (which != PageTransformerHelper.getCurrentTransformerIndex()) {
                                        PageTransformerHelper.setCurrentTransformer(which);
                                        baseBind.transformType.setText(transformerNames[which]);
                                        Local.setSettings(Shaft.sSettings);
                                    }
                                    dialog.dismiss();
                                }
                            })
                            .show();
                }
            });

            baseBind.showRelatedWhenStar.setChecked(Shaft.sSettings.isShowRelatedWhenStar());
            baseBind.showRelatedWhenStar.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setShowRelatedWhenStar(isChecked);
                    Common.showToast(getString(R.string.please_restart_app));
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.showRelatedWhenStarRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.showRelatedWhenStar.performClick();
                }
            });

            baseBind.downloadAutoPostLike.setChecked(Shaft.sSettings.isAutoPostLikeWhenDownload());
            baseBind.downloadAutoPostLike.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setAutoPostLikeWhenDownload(isChecked);
                    Common.showToast(getString(R.string.string_428));
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.downloadAutoPostLikeRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.downloadAutoPostLike.performClick();
                }
            });

            baseBind.autoFollowAfterStar.setChecked(Shaft.sSettings.isAutoFollowAfterStar());
            baseBind.autoFollowAfterStar.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setAutoFollowAfterStar(isChecked);
                    Common.showToast(getString(R.string.string_428));
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.autoFollowAfterStarRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.autoFollowAfterStar.performClick();
                }
            });

            baseBind.autoDownloadAfterStar.setChecked(Shaft.sSettings.isAutoDownloadAfterStar());
            baseBind.autoDownloadAfterStar.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setAutoDownloadAfterStar(isChecked);
                    Common.showToast(getString(R.string.string_428));
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.autoDownloadAfterStarRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.autoDownloadAfterStar.performClick();
                }
            });

            //插画二级详情保持屏幕常亮
            baseBind.illustDetailKeepScreenOn.setChecked(Shaft.sSettings.isIllustDetailKeepScreenOn());
            baseBind.illustDetailKeepScreenOn.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setIllustDetailKeepScreenOn(isChecked);
                    Common.showToast(getString(R.string.string_428));
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.illustDetailKeepScreenOnRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.illustDetailKeepScreenOn.performClick();
                }
            });

            baseBind.isFirebaseEnable.setChecked(Shaft.sSettings.isFirebaseEnable());
            baseBind.isFirebaseEnable.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setFirebaseEnable(isChecked);
                    Local.setSettings(Shaft.sSettings);
                    Common.showToast(getString(R.string.string_428), 2);
                    FirebaseAnalytics.getInstance(mContext).setAnalyticsCollectionEnabled(isChecked);
                }
            });
        }

        // AI 超分辨率模型
        {
            ceui.pixiv.ui.upscale.UpscaleModel saved = ceui.pixiv.ui.upscale.ModelPickerDialog.Companion.getSavedModel();
            baseBind.defaultUpscaleModel.setText(saved != null ? saved.getDisplayName() : getString(R.string.string_not_set));
            baseBind.defaultUpscaleModelRela.setOnClickListener(v -> {
                ceui.pixiv.ui.upscale.ModelPickerDialog.Companion.show(getChildFragmentManager(), model -> {
                    Shaft.sSettings.setDefaultUpscaleModel(model.name());
                    Local.setSettings(Shaft.sSettings);
                    baseBind.defaultUpscaleModel.setText(model.getDisplayName());
                    return kotlin.Unit.INSTANCE;
                });
            });
        }

        // AI 抠图模型
        {
            ceui.pixiv.ui.upscale.RembgModel savedRembg = ceui.pixiv.ui.upscale.RembgModelPickerDialog.Companion.getSavedModel();
            baseBind.defaultRembgModel.setText(savedRembg != null ? savedRembg.getDisplayName() : getString(R.string.string_not_set));
            baseBind.defaultRembgModelRela.setOnClickListener(v -> {
                if (getChildFragmentManager().findFragmentByTag("RembgModelPickerDialog") != null) return;
                ceui.pixiv.ui.upscale.RembgModelPickerDialog dialog = new ceui.pixiv.ui.upscale.RembgModelPickerDialog();
                dialog.setOnModelSelected(model -> {
                    Shaft.sSettings.setDefaultRembgModel(model.name());
                    Local.setSettings(Shaft.sSettings);
                    baseBind.defaultRembgModel.setText(model.getDisplayName());
                    return kotlin.Unit.INSTANCE;
                });
                dialog.show(getChildFragmentManager(), "RembgModelPickerDialog");
            });
        }

        // OCR 模型
        {
            baseBind.ocrModelRela.setOnClickListener(v -> {
                Intent intent = new Intent(mContext, ceui.lisa.activities.TemplateActivity.class);
                intent.putExtra(ceui.lisa.activities.TemplateActivity.EXTRA_FRAGMENT, "漫画OCR模型下载");
                intent.putExtra("manga_ocr_model_name", ceui.pixiv.ui.translate.MangaOcrModel.MANGA_OCR_BASE.name());
                startActivity(intent);
            });
            bindLongPressDeleteModel(
                    baseBind.ocrModelRela,
                    ceui.pixiv.ui.translate.MangaOcrModelManager.INSTANCE,
                    ceui.pixiv.ui.translate.MangaOcrModel.MANGA_OCR_BASE);
        }

        // 翻译模型 (Sakura — ACG 日中翻译，漫画翻译和翻译 demo 都用它)
        {
            baseBind.translationModelRela.setOnClickListener(v -> {
                Intent intent = new Intent(mContext, ceui.lisa.activities.TemplateActivity.class);
                intent.putExtra(ceui.lisa.activities.TemplateActivity.EXTRA_FRAGMENT, "Sakura翻译模型下载");
                intent.putExtra("sakura_model_name", ceui.pixiv.ui.translate.SakuraModel.SAKURA_1_5B.name());
                startActivity(intent);
            });
            bindLongPressDeleteModel(
                    baseBind.translationModelRela,
                    ceui.pixiv.ui.translate.SakuraModelManager.INSTANCE,
                    ceui.pixiv.ui.translate.SakuraModel.SAKURA_1_5B);
        }

        // 缓存
        {
            loadCacheSizeAsync(baseBind.imageCacheSize, LegacyFile.imageCacheFolder(mContext));
            baseBind.clearImageCache.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FileUtils.deleteAllInDir(LegacyFile.imageCacheFolder(mContext));
                    Common.showToast(getString(R.string.success_clearImageCache));
                    loadCacheSizeAsync(baseBind.imageCacheSize, LegacyFile.imageCacheFolder(mContext));
                }
            });

            loadCacheSizeAsync(baseBind.gifCacheSize, LegacyFile.gifCacheFolder(mContext));
            baseBind.clearGifCache.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FileUtils.deleteAllInDir(LegacyFile.gifCacheFolder(mContext));
                    Common.showToast(getString(R.string.success_clearGifCache), 2);
                    loadCacheSizeAsync(baseBind.gifCacheSize, LegacyFile.gifCacheFolder(mContext));
                }
            });

            // 清除批量下载关联数据 —— illust_download_table / download_queue 两张表
            // 的 illustGson 列是重度用户存储占用的大头 (单条 10–30 KB,几万行就上 GB);
            // 加这一行让用户能把"下载管理"reset 回初始状态。落盘的图不会被删,
            // 已下载的内容仍能在系统相册看到。
            loadBulkDownloadCacheSizeAsync(baseBind.bulkDownloadCacheSize);
            baseBind.clearBulkDownloadCache.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showClearBulkDownloadConfirmDialog();
                }
            });
        }

        // 备份与还原
        {
            baseBind.backupRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    QMUIDialog.CheckBoxMessageDialogBuilder builder = new QMUIDialog.CheckBoxMessageDialogBuilder(getActivity());
                    builder
                            .setTitle(getString(R.string.string_420))
                            .setMessage(getString(R.string.string_423))
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addAction(getString(R.string.string_187), new QMUIDialogAction.ActionListener() {
                                @Override
                                public void onClick(QMUIDialog dialog, int index) {
                                    dialog.dismiss();
                                }
                            })
                            .addAction(R.string.sure, new QMUIDialogAction.ActionListener() {
                                @Override
                                public void onClick(QMUIDialog dialog, int index) {
                                    String backupString = BackupUtils.getBackupString(mContext, builder.isChecked());
                                    IllustDownload.downloadBackupFile((BaseActivity<?>) mActivity, "Shaft-Backup.json", backupString, new Callback<Uri>() {
                                        @Override
                                        public void doSomething(Uri t) {
                                            Common.showToast(getString(R.string.backup_success) + Settings.FILE_PATH_BACKUP);
                                        }
                                    });
                                    dialog.dismiss();
                                }
                            })
                            .create()
                            .show();
                }
            });

            baseBind.restoreRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);//必须
                    intent.setType("*/*");//必须
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Uri backupFileUri = Uri.parse("content://com.android.externalstorage.documents/document/primary:"+"Download%2fShaftBackups%2fShaft-Backup.json");
//                        Common.showToast(backupFileUri);
                        intent.putExtra(EXTRA_INITIAL_URI, backupFileUri);
                    }
                    startActivityForResult(intent, Params.REQUEST_CODE_CHOOSE);
                }
            });

            // 上传配置到云端 (moonAPI)
            baseBind.moonUploadRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    long uid = SessionManager.INSTANCE.getLoggedInUid();
                    if (uid <= 0L) {
                        Common.showToast(getString(R.string.moon_login_required));
                        return;
                    }
                    Integer appliedVer = Shaft.sSettings.getMoonAppliedVersions()
                            .get(String.valueOf(uid));
                    String currentVer = (appliedVer != null && appliedVer > 0)
                            ? getString(R.string.moon_upload_current_version, appliedVer)
                            : "";
                    new QMUIDialog.MessageDialogBuilder(getActivity())
                            .setTitle(R.string.moon_upload_title)
                            .setMessage(getString(R.string.moon_upload_message) + currentVer)
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addAction(getString(R.string.string_187), new QMUIDialogAction.ActionListener() {
                                @Override
                                public void onClick(QMUIDialog dialog, int index) {
                                    dialog.dismiss();
                                }
                            })
                            .addAction(R.string.sure, new QMUIDialogAction.ActionListener() {
                                @Override
                                public void onClick(QMUIDialog dialog, int index) {
                                    dialog.dismiss();
                                    MoonSync.uploadToCloud(mActivity, uid);
                                }
                            })
                            .create()
                            .show();
                }
            });

            // 从云端同步配置 (moonAPI)
            baseBind.moonSyncRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    long uid = SessionManager.INSTANCE.getLoggedInUid();
                    if (uid <= 0L) {
                        Common.showToast(getString(R.string.moon_login_required));
                        return;
                    }
                    MoonSync.manualSyncFromCloud(mActivity, uid);
                }
            });
        }

        baseBind.refreshLayout.setRefreshHeader(new FalsifyHeader(mContext));
        baseBind.refreshLayout.setRefreshFooter(new FalsifyFooter(mContext));

        if (!Common.isAndroidQ()) {
            new RxPermissions(this)
                    .requestEachCombined(
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                    .subscribe(permission -> {
                        if (!permission.granted) {
                            Common.showToast(getString(R.string.access_denied));
                            finish();
                        }
                    });
        }
    }

    @Override
    public SmartRefreshLayout getSmartRefreshLayout() {
        return baseBind.refreshLayout;
    }

    private void updateArtworkV3FabOrderLabel() {
        boolean downloadLeft = Shaft.sSettings.isArtworkV3FabDownloadOnLeft();
        baseBind.artworkV3FabOrderSelect.setText(downloadLeft
                ? R.string.artwork_v3_fab_order_download_left
                : R.string.artwork_v3_fab_order_bookmark_left);
    }

    private void applyArtworkV3FabOrderRowVisibility(boolean v3Enabled, boolean animate) {
        int visibility = v3Enabled ? View.VISIBLE : View.GONE;
        if (animate) {
            View parent = (View) baseBind.artworkV3FabOrderRela.getParent();
            if (parent instanceof ViewGroup) {
                AutoTransition transition = new AutoTransition();
                transition.setDuration(220);
                TransitionManager.beginDelayedTransition((ViewGroup) parent, transition);
            }
        }
        baseBind.artworkV3FabOrderRela.setVisibility(visibility);
        baseBind.artworkV3FabOrderDivider.setVisibility(visibility);
    }

    private void setOrderName() {
        final int index = Shaft.sSettings.getBottomBarOrder();
        String[] ORDER_NAME = new String[]{
                getString(R.string.string_343),
                getString(R.string.string_344),
                getString(R.string.string_345),
                getString(R.string.string_346),
                getString(R.string.string_347),
                getString(R.string.string_348),
        };
        baseBind.orderSelect.setText(ORDER_NAME[index]);
    }

    private void setThemeName() {
        final int index = Shaft.sSettings.getThemeIndex();
        baseBind.colorSelect.setText(getString(FragmentColors.COLOR_NAME_CODES[index]));
    }

    private String[] storageNames() {
        return new String[]{
                getString(R.string.setting_storage_pictures),
                getString(R.string.setting_storage_downloads),
                getString(R.string.setting_storage_saf)
        };
    }

    private int currentStorageIndex() {
        StorageChoice cur = DownloadsRegistry.currentImagesStorage();
        if (cur instanceof StorageChoice.Saf) return 2;
        if (cur instanceof StorageChoice.MediaStore
                && ((StorageChoice.MediaStore) cur).getCollection()
                    == StorageChoice.MediaStore.Collection.Downloads) {
            return 1;
        }
        return 0;
    }

    private void refreshStorageLabel() {
        if (baseBind == null) return;
        baseBind.storageChoice.setText(storageNames()[currentStorageIndex()]);
    }

    private void refreshSafDirectPickLabel() {
        if (baseBind == null) return;
        // 取「上一次选过的 SAF 目录」—— legacy rootPathUri 和 v3 DownloadConfig 在 BaseActivity 落库
        // 时已经同步好,这里读 sSettings.rootPathUri 就够。空 = 没选过,显示「点击选择」hint;
        // 有值 = 解出 human-readable 相对路径(primary:Pictures/MyPixiv → /Pictures/MyPixiv)。
        String uriStr = Shaft.sSettings != null ? Shaft.sSettings.getRootPathUri() : null;
        if (TextUtils.isEmpty(uriStr)) {
            baseBind.safDirectPick.setText(R.string.setting_saf_direct_pick_hint);
            return;
        }
        String hint;
        try {
            hint = safFolderHint(Uri.parse(uriStr));
        } catch (Exception e) {
            hint = "";
        }
        if (TextUtils.isEmpty(hint)) {
            baseBind.safDirectPick.setText(R.string.setting_saf_direct_pick_hint);
        } else {
            baseBind.safDirectPick.setText(hint);
        }
    }

    private static String safFolderHint(Uri treeUri) {
        if (treeUri == null) return "";
        try {
            String docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
            int colon = docId.indexOf(':');
            if (colon >= 0 && colon < docId.length() - 1) {
                return "/" + docId.substring(colon + 1);
            }
            return docId;
        } catch (Exception e) {
            String last = treeUri.getLastPathSegment();
            return last != null ? last : "";
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateModelStatus();
        // SAF picker 在 BaseActivity#onActivityResult 落库后，回到 fragment 时把 label 和直拉行右侧拉到现态。
        refreshStorageLabel();
        refreshSafDirectPickLabel();
    }

    private void updateModelStatus() {
        if (baseBind == null) return;
        boolean ocrReady = ceui.pixiv.ui.translate.MangaOcrModelManager.INSTANCE.isModelReady(
                mContext, ceui.pixiv.ui.translate.MangaOcrModel.MANGA_OCR_BASE);
        baseBind.ocrModelStatus.setText(ocrReady
                ? ceui.pixiv.ui.translate.MangaOcrModel.MANGA_OCR_BASE.getDisplayName()
                : getString(R.string.string_model_not_ready, "91MB"));

        boolean sakuraReady = ceui.pixiv.ui.translate.SakuraModelManager.INSTANCE.isModelReady(
                mContext, ceui.pixiv.ui.translate.SakuraModel.SAKURA_1_5B);
        baseBind.translationModelStatus.setText(sakuraReady
                ? ceui.pixiv.ui.translate.SakuraModel.SAKURA_1_5B.getDisplayName()
                : getString(R.string.string_model_not_ready,
                        ceui.pixiv.ui.translate.SakuraModel.SAKURA_1_5B.getSizeLabel()));
    }

    // 长按 Settings 模型行直接删除。未下载状态长按提示用户先下载；
    // 已下载状态弹 QMUI 确认对话框，确认后删除并刷新右侧状态文字。
    private void bindLongPressDeleteModel(View row,
                                          ceui.pixiv.ui.common.ModelDownloadManager mgr,
                                          ceui.pixiv.ui.common.DownloadableModel model) {
        row.setOnLongClickListener(v -> {
            if (mContext == null) return false;
            if (!mgr.isModelReady(mContext, model)) {
                Common.showToast(getString(R.string.string_rembg_model_long_press_to_delete));
                return true;
            }
            new QMUIDialog.MessageDialogBuilder(getActivity())
                    .setTitle(R.string.string_rembg_model_delete_confirm_title)
                    .setMessage(getString(R.string.string_rembg_model_delete_confirm_message, model.getDisplayName()))
                    .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                    .addAction(0, getString(R.string.string_cancel), QMUIDialogAction.ACTION_PROP_NEUTRAL,
                            (d, i) -> d.dismiss())
                    .addAction(0, getString(R.string.string_rembg_model_delete), QMUIDialogAction.ACTION_PROP_NEGATIVE,
                            (d, i) -> {
                                d.dismiss();
                                mgr.deleteModel(mContext, model);
                                updateModelStatus();
                                Common.showToast(getString(R.string.string_rembg_model_delete_done, model.getDisplayName()));
                            })
                    .show();
            return true;
        });
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable @org.jetbrains.annotations.Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == Params.REQUEST_CODE_CHOOSE && resultCode == RESULT_OK && data != null) {
            try {
                Uri uri = data.getData();
                String fileString = new String(UriUtils.uri2Bytes(uri));
                boolean restoreResult = BackupUtils.restoreBackups(mContext, fileString);
                Common.showToast(restoreResult ? getString(R.string.restore_success) : getString(R.string.restore_failed));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void loadCacheSizeAsync(TextView target, File folder) {
        target.setText("…");
        new Thread(() -> {
            String size = FileUtils.getSize(folder);
            target.post(() -> {
                if (isAdded()) {
                    target.setText(size);
                }
            });
        }, "cache-size-calc").start();
    }

    /**
     * Settings 右侧"清除批量下载关联数据"那一行的大小估算 —— 算 illust_download_table /
     * download_queue 里 illustGson 列的字节 + staging_dl/ 实际占用。SQL 计数走子线程,
     * 大表 (Mio 用户 50k 行) 仍是毫秒级。
     */
    private void loadBulkDownloadCacheSizeAsync(TextView target) {
        target.setText("…");
        new Thread(() -> {
            long bytes;
            try {
                bytes = ceui.pixiv.db.bulkclean.BulkDownloadCacheCleaner
                        .computeReclaimableBytes(mContext.getApplicationContext());
            } catch (Throwable t) {
                bytes = 0L;
            }
            final String size = android.text.format.Formatter.formatShortFileSize(mContext, bytes);
            target.post(() -> {
                if (isAdded()) {
                    target.setText(size);
                }
            });
        }, "bulk-dl-size-calc").start();
    }

    /**
     * 跟 DoneListV3Fragment.showClearDoneConfirmDialog 一样的 destructive 二次确认 ——
     * 文案明确说"已下好的图不会被删",避免用户因恐慌而不敢清理。
     */
    private void showClearBulkDownloadConfirmDialog() {
        if (getActivity() == null || getActivity().isFinishing() || getActivity().isDestroyed()) return;
        new QMUIDialog.MessageDialogBuilder(getActivity())
                .setTitle(R.string.clear_bulk_download_cache)
                .setMessage(R.string.clear_bulk_download_cache_message)
                .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                .addAction(R.string.cancel, (d, idx) -> d.dismiss())
                .addAction(0, R.string.sure, QMUIDialogAction.ACTION_PROP_NEGATIVE, (d, idx) -> {
                    d.dismiss();
                    runBulkDownloadCacheWipe();
                })
                .create()
                .show();
    }

    /**
     * 真正执行 wipe。VACUUM 1.5GB DB 在低端机上能跑 30s+,期间挂个 progress dialog
     * 不让用户重复点;wipe 内部把队列 / Manager 都 stop 了,再次写库的入口已经关掉。
     */
    private void runBulkDownloadCacheWipe() {
        if (getActivity() == null || getActivity().isFinishing() || getActivity().isDestroyed()) return;
        final android.content.Context appCtx = mContext.getApplicationContext();
        final com.qmuiteam.qmui.widget.dialog.QMUITipDialog progress =
                new com.qmuiteam.qmui.widget.dialog.QMUITipDialog.Builder(getActivity())
                        .setIconType(com.qmuiteam.qmui.widget.dialog.QMUITipDialog.Builder.ICON_TYPE_LOADING)
                        .setTipWord(getString(R.string.clear_bulk_download_cache_progress))
                        .create();
        progress.setCancelable(false);
        progress.show();

        // 用 Main looper 的 Handler 而不是绑某个 View 的 post —— 旋屏时 baseBind 会被
        // DataBinding 重建,绑 view 的 post 可能根本不跑,留下孤儿 progress dialog
        // 抓着旧 activity 触发 WindowLeaked。Handler 是 activity-agnostic,稳。
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

        new Thread(() -> {
            try {
                ceui.pixiv.db.bulkclean.BulkDownloadCacheCleaner.wipe(appCtx);
            } catch (Throwable t) {
                android.util.Log.w("FragmentSettings", "bulk download wipe failed", t);
            }
            main.post(() -> {
                // 先无条件 dismiss —— 即使 fragment 已被销毁,旧 activity 上挂着的
                // 这个 dialog 也必须解掉,否则 WindowLeaked。
                try { progress.dismiss(); } catch (Throwable ignored) {}
                if (isAdded() && baseBind != null) {
                    Common.showToast(getString(R.string.success_clear_bulk_download_cache));
                    loadBulkDownloadCacheSizeAsync(baseBind.bulkDownloadCacheSize);
                }
            });
        }, "bulk-dl-wipe").start();
    }

    private String currentLanguageDisplay() {
        if (ceui.pixiv.i18n.AppLocales.INSTANCE.isFollowingSystem()) {
            return getString(R.string.language_follow_system);
        }
        Locale loc = ceui.pixiv.i18n.AppLocales.INSTANCE.currentLocale();
        return ceui.pixiv.i18n.AppLocales.INSTANCE.displayName(loc.toLanguageTag());
    }
}
