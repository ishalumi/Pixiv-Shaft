package ceui.lisa.core;

/**
 * 屏蔽内容过多时的自动加载策略 (#729)
 * <p>
 * {@link Mapper} 会把已屏蔽的作品从每页响应里移除。当用户屏蔽的 tag/作品/画师过多时，
 * 某一页可能被整页过滤掉，列表上没有任何条目：没有条目就无法滑动，
 * 无法滑动就无法触发加载下一页，用户被卡死在"暂无数据"页面。
 * <p>
 * 这个类决定一页加载完成后是否要自动加载下一页：
 * <ul>
 *     <li>当前列表为空 + 还有下一页 + 连续自动加载次数未达上限 → 自动加载</li>
 *     <li>一旦有内容显示出来，交还给正常的滑动加载</li>
 *     <li>连续 {@link #MAX_AUTO_LOAD_TIMES} 页都被过滤光则停止，避免无限请求</li>
 * </ul>
 * 不依赖任何 Android API，便于纯 JVM 单元测试。
 */
public class AutoLoadPolicy {

    /**
     * 一次刷新最多连续自动加载多少页
     */
    public static final int MAX_AUTO_LOAD_TIMES = 5;

    private int autoLoadCount = 0;

    /**
     * 用户主动刷新时调用，重置自动加载预算
     */
    public void reset() {
        autoLoadCount = 0;
    }

    /**
     * 一页加载成功后调用，决定是否需要自动加载下一页
     *
     * @param visibleItemCount 过滤后当前列表已有条目数
     * @param nextUrl          下一页地址，空表示没有更多数据
     * @return true 表示应该立即自动加载下一页
     */
    public boolean shouldAutoLoad(int visibleItemCount, String nextUrl) {
        if (visibleItemCount > 0) {
            return false;
        }
        if (nextUrl == null || nextUrl.isEmpty()) {
            return false;
        }
        if (autoLoadCount >= MAX_AUTO_LOAD_TIMES) {
            return false;
        }
        autoLoadCount++;
        return true;
    }

    public int getAutoLoadCount() {
        return autoLoadCount;
    }
}
