package ceui.lisa.database

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * 单车道 dispatcher，专门给 [DownloadDao.hasDownloadRecordByIllustId] 这类
 * “这幅画下过没” 的探测串行化。
 *
 * 该查询是对 illustGson blob 列的前导通配 LIKE 全表扫描（illustId 埋在 JSON
 * 里，无法走索引），30000+ 条下载库下单次要几百 ms。多 P 详情页 ViewPager 每页
 * onResume 各发一次，能同时并发好几条，把 Room WAL 的读连接占满；主线程 onCreate
 * 里的同步 DB 查询（UActivity / UserActivityV3）就拿不到连接、卡在
 * SQLiteConnectionPool.waitForConnection → ANR。
 *
 * 串行化后探测最多占一条读连接，池子始终给 UI 线程和下载写入留有余量。慢是慢，
 * 但只慢在后台徽标，不再拖垮别的 DB 使用方。
 */
@OptIn(ExperimentalCoroutinesApi::class)
val downloadProbeDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
