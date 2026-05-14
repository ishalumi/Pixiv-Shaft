package ceui.pixiv.chat.base

import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras

/**
 * Create a ViewModel with constructor parameters directly:
 * ```
 * private val vm by viewModels { MyViewModel(dependency) }
 * ```
 */
inline fun <reified T : ViewModel> Fragment.viewModels(
    noinline creator: () -> T
): Lazy<T> = viewModels(factoryProducer = { viewModelFactory(creator) })

fun <T : ViewModel> viewModelFactory(creator: () -> T): ViewModelProvider.Factory {
    return object : ViewModelProvider.Factory {
        override fun <VM : ViewModel> create(modelClass: Class<VM>, extras: CreationExtras): VM {
            @Suppress("UNCHECKED_CAST")
            return creator() as VM
        }
    }
}
