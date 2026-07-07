package ceui.lisa.utils;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import ceui.lisa.R;

/**
 * Origin:https://github.com/RikkaW/SearchByImage.git
 * <p>
 * Created by Rikka on 2015/12/18.
 */
public class ClipBoardUtils {

    public static void putTextIntoClipboard(Context context, String text) {
        putTextIntoClipboard(context, text, true);
    }

    public static void putTextIntoClipboard(Context context, String text, boolean showHint) {
        boolean ok = setPrimaryClip(context, ClipData.newPlainText("copy text", text));
        if (!ok) {
            Common.showToast(context.getString(R.string.msg_copy_failed));
            return;
        }
        if (showHint) {
            Common.showToast(text + context.getString(R.string.has_copyed));
        }
    }

    /**
     * 安全写剪贴板。部分机型 / 多用户场景(工作资料、副用户、某些 OEM 剪贴板服务)下，
     * {@link ClipboardManager#setPrimaryClip} 会抛 SecurityException
     * ("need INTERACT_ACROSS_USERS ... to check hasUserRestriction") 或 IllegalStateException，
     * App 无法靠申请权限规避，这里 catch 住返回 false 兜底，别让复制动作把进程带崩。
     *
     * @return true 写入成功；false 被系统拒绝或服务缺失(调用方按需 toast 失败提示)
     */
    public static boolean setPrimaryClip(Context context, ClipData clipData) {
        ClipboardManager clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager == null) {
            return false;
        }
        try {
            clipboardManager.setPrimaryClip(clipData);
            return true;
        } catch (Exception e) {
            Common.showLog("setPrimaryClip failed: " + e);
            return false;
        }
    }

    public static String getClipboardContent(Context context) {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            ClipData data = cm.getPrimaryClip();
            if (data != null && data.getItemCount() > 0) {
                ClipData.Item item = data.getItemAt(0);
                if (item != null) {
                    CharSequence sequence = item.coerceToText(context);
                    if (sequence != null) {
                        return sequence.toString();
                    }
                }
            }
        }
        return null;
    }
}
