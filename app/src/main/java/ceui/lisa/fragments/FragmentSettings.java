package ceui.lisa.fragments;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.os.Bundle;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageButton;
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
import ceui.loxia.CloudHistoryConsent;
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

            // Google Play 渠道合规：邮箱备份会把用户邮箱传到 pixshaft-api，而数据安全表单
            // 未声明「电子邮件地址」收集（40760 被 Play 政策标记）。lite 渠道隐藏该入口。
            if (ceui.lisa.BuildConfig.IS_LITE) {
                baseBind.accountBackupDivider.setVisibility(View.GONE);
                baseBind.accountBackup.setVisibility(View.GONE);
            } else {
                baseBind.accountBackup.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent intent = new Intent(mContext, TemplateActivity.class);
                        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "邮箱备份");
                        intent.putExtra("mode", "backup");
                        startActivity(intent);
                    }
                });
            }

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

            //图片加速代理（issue #865）：Pixiv 官方 / pixiv.cat / 自定义反代
            refreshImageHostSummary();
            baseBind.imageHostRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showImageHostPicker();
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

        // 试验性
        {
            boolean chatRoomOn = Shaft.sSettings.isShowChatRoomEntry();
            baseBind.showChatRoomEntry.setChecked(chatRoomOn);
            // push banner 行只在「聊天室入口」开启时展示
            int bannerRowVisibility = chatRoomOn ? View.VISIBLE : View.GONE;
            baseBind.showChatRoomPushBannerRela.setVisibility(bannerRowVisibility);
            baseBind.showChatRoomPushBannerDivider.setVisibility(bannerRowVisibility);
            baseBind.showChatRoomPushBanner.setChecked(Shaft.sSettings.isShowChatRoomPushBanner());
            baseBind.showPlazaEntry.setChecked(Shaft.sSettings.isShowPlazaEntry());

            baseBind.showChatRoomEntry.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setShowChatRoomEntry(isChecked);
                    // push banner 行随「聊天室入口」联动显隐;banner 是否真正弹出由 ChatBannerBridge
                    // 同时校验 showChatRoomEntry && showChatRoomPushBanner 决定,所以这里只切显隐、
                    // 保留子开关自身的值(关掉再打开聊天室不会丢失用户的 push 偏好)。
                    int visibility = isChecked ? View.VISIBLE : View.GONE;
                    baseBind.showChatRoomPushBannerRela.setVisibility(visibility);
                    baseBind.showChatRoomPushBannerDivider.setVisibility(visibility);
                    Common.showToast(getString(R.string.string_428));
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.showChatRoomEntryRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.showChatRoomEntry.performClick();
                }
            });

            baseBind.showChatRoomPushBanner.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setShowChatRoomPushBanner(isChecked);
                    Common.showToast(getString(R.string.string_428));
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.showChatRoomPushBannerRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.showChatRoomPushBanner.performClick();
                }
            });

            baseBind.showPlazaEntry.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setShowPlazaEntry(isChecked);
                    Common.showToast(getString(R.string.string_428));
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.showPlazaEntryRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.showPlazaEntry.performClick();
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

            // 浏览记录云同步开关 + 清除云端记录 (issue #889)
            baseBind.cloudHistorySync.setChecked(Shaft.sSettings.isCloudHistorySync());
            baseBind.cloudHistorySync.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    CloudHistoryConsent.setEnabled(isChecked);
                    Common.showToast(getString(R.string.string_428), 2);
                }
            });
            baseBind.cloudHistorySyncRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.cloudHistorySync.performClick();
                }
            });
            baseBind.clearCloudHistoryRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    CloudHistoryConsent.clearCloudHistory(mActivity, SessionManager.INSTANCE.getLoggedInUid());
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

            // aria2 远程下载（#692）—— 把下载任务发给 NAS / 远程服务器上的 aria2
            refreshAria2Label();
            baseBind.aria2Rela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "aria2远程下载");
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
            // SAF 模式下这个 setting 被锁住,因为 SafBackend.replace 已经 override 成
            // 不检测重复(目录 3 万+ 文件后 findFile 会退化成 O(N²)),所以 Skip/Replace/
            // Rename 三个语义不再适用,统一显示成「自动产生副本 · 不可改」+ toast 解释。
            final String[] POLICY_NAMES = new String[]{
                    getString(R.string.download_path_policy_skip),
                    getString(R.string.download_path_policy_replace),
                    getString(R.string.download_path_policy_rename)
            };
            final OverwritePolicy[] POLICY_VALUES = OverwritePolicy.values();
            refreshOverwritePolicyRow();
            baseBind.overwritePolicyRela.setOnClickListener(v -> {
                if (DownloadsRegistry.isSaf()) {
                    new QMUIDialog.MessageDialogBuilder(mActivity)
                            .setTitle(R.string.setting_overwrite_policy_saf_locked_title)
                            .setMessage(R.string.setting_overwrite_policy_saf_locked_message)
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addAction(0, getString(R.string.button_ok), (dialog, index) -> dialog.dismiss())
                            .show();
                    return;
                }
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
                            refreshOverwritePolicyRow();
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
                                refreshOverwritePolicyRow();
                            } else if (which == 1) {
                                DownloadsRegistry.applyGlobalStorage(
                                        new StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Downloads));
                                refreshStorageLabel();
                                refreshOverwritePolicyRow();
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

            // 看图时保留状态栏(刘海/挖孔)区域（issue #724），默认关闭。
            baseBind.keepStatusBarWhenViewImage.setChecked(Shaft.sSettings.isKeepStatusBarWhenViewImage());
            baseBind.keepStatusBarWhenViewImage.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setKeepStatusBarWhenViewImage(isChecked);
                    Common.showToast(getString(R.string.string_428));
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.keepStatusBarWhenViewImageRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.keepStatusBarWhenViewImage.performClick();
                }
            });

            // 同义词词典功能总开关（issue #904），默认关闭。
            // 关闭时所有相关 UI（详情页匹配框/长按菜单项/管理页入口/自动导入/自动勾选）完全隐藏。
            baseBind.synonymDictEnable.setChecked(Shaft.sSettings.isSynonymDictEnabled());
            baseBind.synonymDictEntryContainer.setVisibility(
                    Shaft.sSettings.isSynonymDictEnabled() ? View.VISIBLE : View.GONE);
            baseBind.synonymDictEnable.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setSynonymDictEnabled(isChecked);
                    Local.setSettings(Shaft.sSettings);
                    baseBind.synonymDictEntryContainer.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                    if (isChecked) {
                        // 首次打开：后台静默导入内置词典（已导入过的设备跳过）
                        final android.content.Context appContext = mContext.getApplicationContext();
                        new Thread(() ->
                                ceui.pixiv.ui.synonym.SynonymBuiltinDict.autoImportIfNeeded(appContext)
                        ).start();
                    }
                }
            });
            baseBind.synonymDictEnableRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.synonymDictEnable.performClick();
                }
            });

            // 同义词词典管理入口（仅开关打开时可见）
            baseBind.synonymDictRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "同义词词典");
                    startActivity(intent);
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

            //插画二级详情：自定义双击放大模式（PR#900）
            baseBind.useCustomLongPressResetGroup.setVisibility(
                    Shaft.sSettings.isUseCustomDoubleTapZoom() ? View.VISIBLE : View.GONE);
            baseBind.useCustomDoubleTapZoom.setChecked(Shaft.sSettings.isUseCustomDoubleTapZoom());
            baseBind.useCustomDoubleTapZoom.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setUseCustomDoubleTapZoom(isChecked);
                    Common.showToast(getString(R.string.string_428));
                    Local.setSettings(Shaft.sSettings);
                    ViewGroup CustomLongPressGroup = (ViewGroup) baseBind.useCustomLongPressResetGroup.getParent();
                    if (CustomLongPressGroup != null) {
                        TransitionManager.beginDelayedTransition(CustomLongPressGroup, new AutoTransition());
                    }
                    baseBind.useCustomLongPressResetGroup.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                }
            });

            baseBind.useCustomDoubleTapZoomRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.useCustomDoubleTapZoom.performClick();
                }
            });

            // 初始化缩放增量数值调节
            setupCustomZoomScaleAdjust();

            //长按复位
            baseBind.useCustomLongPressReset.setChecked(Shaft.sSettings.isUseCustomLongPressReset());
            baseBind.useCustomLongPressReset.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setUseCustomLongPressReset(isChecked);
                    Common.showToast(getString(R.string.string_428));
                    Local.setSettings(Shaft.sSettings);
                }
            });

            baseBind.useCustomThreeLevelZoom.setChecked(Shaft.sSettings.isUseThreeLevelZoo());
            baseBind.useCustomThreeLevelZoom.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setUseThreeLevelZoo(isChecked);
                    Common.showToast(getString(R.string.string_428));
                    Local.setSettings(Shaft.sSettings);
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

        // 气泡检测模型 (comic-text-detector) — 漫画翻译流水线的文本框/气泡检测阶段
        {
            baseBind.bubbleDetectorModelRela.setOnClickListener(v -> {
                Intent intent = new Intent(mContext, ceui.lisa.activities.TemplateActivity.class);
                intent.putExtra(ceui.lisa.activities.TemplateActivity.EXTRA_FRAGMENT, "漫画文本框检测模型下载");
                intent.putExtra("ctd_model_name", ceui.pixiv.ui.translate.ComicTextDetectorModel.CTD_BASE.name());
                startActivity(intent);
            });
            bindLongPressDeleteModel(
                    baseBind.bubbleDetectorModelRela,
                    ceui.pixiv.ui.translate.ComicTextDetectorModelManager.INSTANCE,
                    ceui.pixiv.ui.translate.ComicTextDetectorModel.CTD_BASE);
        }

        // 漫画 OCR 识别模型 (manga-ocr) — 把检测出的气泡里的日文识别成文本
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

    private void setupCustomZoomScaleAdjust() {
        TextView scaleDisplay = baseBind.customZoomScaleDisplay;
        ImageButton decreaseBtn = baseBind.customZoomScaleDecrease;
        ImageButton increaseBtn = baseBind.customZoomScaleIncrease;

        // 获取当前保存的缩放增量值
        float currentScale = Shaft.sSettings.getCustomZoomAddScale();
        if (currentScale < 1.1f || currentScale > 3.0f) {
            currentScale = 1.8f; // 默认值
            Shaft.sSettings.setCustomZoomAddScale(currentScale);
        }

        // 显示当前值
        scaleDisplay.setText(String.format(Locale.US, "%.1f", currentScale));

        // 减少按钮
        decreaseBtn.setOnClickListener(v -> {
            float scale = Shaft.sSettings.getCustomZoomAddScale();
            if (scale > 1.1f) {
                scale = Math.round((scale - 0.1f) * 10f) / 10f;
                updateZoomScale(scale, scaleDisplay);
            }
        });

        // 增加按钮
        increaseBtn.setOnClickListener(v -> {
            float scale = Shaft.sSettings.getCustomZoomAddScale();
            if (scale < 3.0f) {
                scale = Math.round((scale + 0.1f) * 10f) / 10f;
                updateZoomScale(scale, scaleDisplay);
            }
        });

        // 长按快速调节（可选）
        setupLongPressAdjust(decreaseBtn, increaseBtn, scaleDisplay);
    }

    private void updateZoomScale(float newScale, TextView scaleDisplay) {
        // 保存到 Shaft.sSettings
        Shaft.sSettings.setCustomZoomAddScale(newScale);

        // 更新显示
        scaleDisplay.setText(String.format(Locale.US, "%.1f", newScale));

        // 保存设置
        Local.setSettings(Shaft.sSettings);
    }

    // 长按快速调节功能（可选）
    private Handler autoAdjustHandler;
    private Runnable autoAdjustRunnable;

    @SuppressLint("ClickableViewAccessibility")
    private void setupLongPressAdjust(ImageButton decreaseBtn,
                                      ImageButton increaseBtn,
                                      TextView scaleDisplay) {
        decreaseBtn.setOnLongClickListener(v -> {
            startAutoAdjust(scaleDisplay, false);
            return true;
        });

        increaseBtn.setOnLongClickListener(v -> {
            startAutoAdjust(scaleDisplay, true);
            return true;
        });

        View.OnTouchListener stopAdjustListener = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_UP ||
                        event.getAction() == MotionEvent.ACTION_CANCEL) {
                    stopAutoAdjust();
                }
                return false;
            }
        };

        decreaseBtn.setOnTouchListener(stopAdjustListener);
        increaseBtn.setOnTouchListener(stopAdjustListener);
    }

    private void startAutoAdjust(TextView scaleDisplay, boolean isIncrease) {
        stopAutoAdjust();

        if (autoAdjustHandler == null) {
            autoAdjustHandler = new Handler(Looper.getMainLooper());
        }

        autoAdjustRunnable = new Runnable() {
            @Override
            public void run() {
                float scale = Shaft.sSettings.getCustomZoomAddScale();

                if (isIncrease && scale < 3.0f) {
                    scale = Math.round((scale + 0.1f) * 10f) / 10f;
                    updateZoomScale(scale, scaleDisplay);
                } else if (!isIncrease && scale > 1.1f) {
                    scale = Math.round((scale - 0.1f) * 10f) / 10f;
                    updateZoomScale(scale, scaleDisplay);
                }

                autoAdjustHandler.postDelayed(this, 100);
            }
        };

        autoAdjustHandler.postDelayed(autoAdjustRunnable, 500);
    }

    private void stopAutoAdjust() {
        if (autoAdjustHandler != null && autoAdjustRunnable != null) {
            autoAdjustHandler.removeCallbacks(autoAdjustRunnable);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopAutoAdjust();
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

    private void refreshOverwritePolicyRow() {
        if (baseBind == null) return;
        if (DownloadsRegistry.isSaf()) {
            baseBind.overwritePolicy.setText(R.string.setting_overwrite_policy_saf_locked);
            baseBind.overwritePolicy.setAlpha(0.5f);
        } else {
            OverwritePolicy cur = DownloadsRegistry.getStore().loadOrFallback().getDefaults().getOverwrite();
            String[] names = new String[]{
                    getString(R.string.download_path_policy_skip),
                    getString(R.string.download_path_policy_replace),
                    getString(R.string.download_path_policy_rename)
            };
            baseBind.overwritePolicy.setText(names[cur.ordinal()]);
            baseBind.overwritePolicy.setAlpha(1.0f);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateModelStatus();
        // SAF picker 在 BaseActivity#onActivityResult 落库后，回到 fragment 时把 label 拉到现态。
        refreshStorageLabel();
        refreshOverwritePolicyRow();
        // 从 aria2 设置子页返回时把开关状态同步到入口行。
        refreshAria2Label();
    }

    /** aria2 远程下载入口行的状态文字：已启用时显示 RPC 地址，否则显示功能简介。 */
    private void refreshAria2Label() {
        if (baseBind == null) return;
        if (Shaft.sSettings.isAria2Enabled() && !TextUtils.isEmpty(Shaft.sSettings.getAria2RpcUrl())) {
            baseBind.aria2Desc.setText(getString(R.string.aria2_status_enabled, Shaft.sSettings.getAria2RpcUrl()));
        } else {
            baseBind.aria2Desc.setText(getString(R.string.aria2_settings_entry_desc));
        }
    }

    private void updateModelStatus() {
        if (baseBind == null) return;

        ceui.pixiv.ui.translate.ComicTextDetectorModel ctd = ceui.pixiv.ui.translate.ComicTextDetectorModel.CTD_BASE;
        boolean ctdReady = ceui.pixiv.ui.translate.ComicTextDetectorModelManager.INSTANCE.isModelReady(mContext, ctd);
        baseBind.bubbleDetectorModelStatus.setText(ctdReady
                ? ctd.getDisplayName()
                : getString(R.string.string_model_not_ready, ctd.getSizeLabel()));

        ceui.pixiv.ui.translate.MangaOcrModel ocr = ceui.pixiv.ui.translate.MangaOcrModel.MANGA_OCR_BASE;
        boolean ocrReady = ceui.pixiv.ui.translate.MangaOcrModelManager.INSTANCE.isModelReady(mContext, ocr);
        baseBind.ocrModelStatus.setText(ocrReady
                ? ocr.getDisplayName()
                : getString(R.string.string_model_not_ready, ocr.getSizeLabel()));
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

    // ── 图片加速代理（issue #865） ──────────────────────────────────────
    // 三档：0=Pixiv 官方 / 1=pixiv.cat / 2=自定义反代。只写 Settings，下次启动经
    // Shaft.onCreate 的 ImageHostManager.hydrate 生效（图片 OkHttpClient 启动时一次性
    // 构建、被 Glide 持有，与直连开关同款限制），故切换后提示重启。

    // 选项顺序 == ImageHostManager.Mode 的 ordinal == Settings.imageHostMode。
    private static final int IMAGE_HOST_MODE_CUSTOM =
            ceui.lisa.http.ImageHostManager.Mode.CUSTOM.ordinal();

    private void refreshImageHostSummary() {
        int mode = Shaft.sSettings.getImageHostMode();
        String summary;
        if (mode == ceui.lisa.http.ImageHostManager.Mode.PIXIV_CAT.ordinal()) {
            summary = getString(R.string.image_host_pixiv_cat);
        } else if (mode == ceui.lisa.http.ImageHostManager.Mode.PIXIV_RE.ordinal()) {
            summary = getString(R.string.image_host_pixiv_re);
        } else if (mode == ceui.lisa.http.ImageHostManager.Mode.PIXIV_NL.ordinal()) {
            summary = getString(R.string.image_host_pixiv_nl);
        } else if (mode == IMAGE_HOST_MODE_CUSTOM) {
            String host = Shaft.sSettings.getCustomImageHost();
            summary = TextUtils.isEmpty(host) ? getString(R.string.image_host_custom) : host;
        } else {
            summary = getString(R.string.image_host_pixiv_official);
        }
        baseBind.imageHostValue.setText(summary);
    }

    private void showImageHostPicker() {
        // 顺序必须与 ImageHostManager.Mode 的 ordinal 一致（index == mode 值）。
        String[] items = {
                getString(R.string.image_host_pixiv_official),
                getString(R.string.image_host_pixiv_cat),
                getString(R.string.image_host_pixiv_re),
                getString(R.string.image_host_pixiv_nl),
                getString(R.string.image_host_custom),
        };
        int current = Shaft.sSettings.getImageHostMode();
        if (current < 0 || current >= items.length) {
            current = 0;
        }
        new QMUIDialog.CheckableDialogBuilder(mContext)
                .setCheckedIndex(current)
                .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                .addItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        if (which == IMAGE_HOST_MODE_CUSTOM) {
                            promptCustomImageHost();
                        } else {
                            applyImageHostMode(which);
                        }
                    }
                })
                .create()
                .show();
    }

    private void promptCustomImageHost() {
        final QMUIDialog.EditTextDialogBuilder builder = new QMUIDialog.EditTextDialogBuilder(mContext);
        builder.setTitle(R.string.image_host_custom)
                .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                .setPlaceholder(getString(R.string.image_host_custom_hint))
                .setDefaultText(Shaft.sSettings.getCustomImageHost())
                .setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI)
                .addAction(getString(R.string.string_142), (dialog, index) -> dialog.dismiss())
                .addAction(getString(R.string.sure), (dialog, index) -> {
                    CharSequence text = builder.getEditText().getText();
                    String host = text == null ? "" : text.toString().trim();
                    if (TextUtils.isEmpty(host)) {
                        Common.showToast(getString(R.string.image_host_custom_empty));
                        return;
                    }
                    Shaft.sSettings.setCustomImageHost(host);
                    applyImageHostMode(IMAGE_HOST_MODE_CUSTOM);
                    dialog.dismiss();
                })
                .create()
                .show();
    }

    private void applyImageHostMode(int mode) {
        Shaft.sSettings.setImageHostMode(mode);
        Local.setSettings(Shaft.sSettings);
        refreshImageHostSummary();
        Common.showToast(getString(R.string.image_host_restart_hint), 2);
    }
}
