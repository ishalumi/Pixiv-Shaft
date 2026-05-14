package ceui.pixiv.chat.base

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Shorthand for the lifecycle-aware collect pattern:
 * ```
 * viewLifecycleOwner.lifecycleScope.launch {
 *     viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { … }
 * }
 * ```
 */
fun Fragment.launchSuspend(block: suspend CoroutineScope.() -> Unit) {
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED, block)
    }
}

/**
 * Lazy, lifecycle-aware ViewBinding delegate for Fragments that inflate their
 * layout via a constructor resource id (e.g. `Fragment(R.layout.my_layout)`).
 *
 * The binding is created on first access (after `onCreateView`) and automatically
 * cleared in `onDestroyView`, preventing memory leaks.
 *
 * ```kotlin
 * class MyListFragment : PagedListFragment<Item>(R.layout.fragment_my_list) {
 *     private val binding by viewBinding(FragmentMyListBinding::bind)
 *
 *     override fun onListViewCreated(view: View, savedInstanceState: Bundle?) {
 *         binding.toolbar.title = "My List"
 *     }
 * }
 * ```
 *
 * This bridges ViewBinding and [PagedListFragment]
 * (layout via resource id): any Fragment subclass can opt into ViewBinding
 * without changing its inheritance hierarchy.
 */
fun <VB : ViewBinding> Fragment.viewBinding(
    bind: (View) -> VB
): ReadOnlyProperty<Fragment, VB> = ViewBindingDelegate(bind)

private class ViewBindingDelegate<VB : ViewBinding>(
    private val bind: (View) -> VB
) : ReadOnlyProperty<Fragment, VB> {

    private var binding: VB? = null

    override fun getValue(thisRef: Fragment, property: KProperty<*>): VB {
        binding?.let { return it }

        val viewLifecycle = thisRef.viewLifecycleOwner.lifecycle
        check(viewLifecycle.currentState.isAtLeast(Lifecycle.State.INITIALIZED)) {
            "Cannot access ViewBinding before onCreateView or after onDestroyView"
        }

        viewLifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                owner.lifecycle.removeObserver(this)
                // Post so binding is still accessible during onDestroyView()
                Handler(Looper.getMainLooper()).post { binding = null }
            }
        })

        return bind(thisRef.requireView()).also { binding = it }
    }
}
