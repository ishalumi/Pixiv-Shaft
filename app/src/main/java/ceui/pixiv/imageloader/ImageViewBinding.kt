package ceui.pixiv.imageloader

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * 生命周期安全地观察一个 [ImageLoadTask]。返回 [Disposable] 可主动取消;观察本身随 [owner] 的
 * STARTED 生命周期自动挂起/恢复、DESTROYED 后结束——结构上根治了 ui/task(issue #912)那种
 * observer 绑生命周期却不摘、随复用无上限堆积的泄漏。
 */
fun ImageLoadTask.observeState(
    owner: LifecycleOwner,
    onState: (ImageLoadState) -> Unit,
): Disposable {
    val job = owner.lifecycleScope.launch {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            state.collect { onState(it) }
        }
    }
    return Disposable { job.cancel() }
}
