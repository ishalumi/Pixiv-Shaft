package ceui.pixiv.ui.search

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import ceui.lisa.R
import ceui.lisa.databinding.FragmentSearchAllBinding
import ceui.loxia.Client
import ceui.loxia.ObjectPool
import ceui.loxia.ProgressIndicator
import ceui.loxia.hideKeyboard
import ceui.loxia.launchSuspend
import ceui.loxia.pushFragment
import ceui.loxia.showKeyboard
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.common.viewModels
import ceui.pixiv.ui.detail.ArtworksMap
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.alertYesOrCancel
import kotlinx.coroutines.delay

class SearchAllFragment : PixivFragment(R.layout.fragment_search_all) {

    private val binding by viewBinding(FragmentSearchAllBinding::bind)
    private val searchViewModel by constructVM({ "" }) { word ->
        SearchViewModel(word)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding.toolbarLayout, binding.contentGroup)
        binding.viewModel = searchViewModel
        binding.toolbarLayout.naviTitle.text = getString(R.string.search)
        binding.clearSearch.setOnClick {
            searchViewModel.inputDraft.value = ""
        }
        binding.idSearchIllust.setOnClick { sender ->
            checkAndNextId(sender) { id ->
                val illust = Client.appApi.getIllust(id).illust
                if (illust != null) {
                    ArtworksMap.store[fragmentViewModel.fragmentUniqueId] = listOf(id)
                    ObjectPool.update(illust)
                    onClickIllust(illust.id)
                }
            }
        }
        binding.idSearchUser.setOnClick { sender ->
            checkAndNextId(sender) { id ->
                val userResp = Client.appApi.getUserProfile(id)
                userResp.user?.let { user ->
                    ObjectPool.update(user)
                }
                onClickUser(id)
            }
        }
        binding.idSearchNovel.setOnClick { sender ->
            checkAndNextId(sender) { id ->
                val novel = Client.appApi.getNovel(id).novel
                if (novel != null) {
                    ArtworksMap.store[fragmentViewModel.fragmentUniqueId] = listOf(id)
                    ObjectPool.update(novel)
                    onClickNovel(novel.id)
                }
            }
        }
        binding.keywordSearch.setOnClick { sender ->
            checkAndNext(sender) { word ->
                pushFragment(R.id.navigation_search_viewpager, SearchViewPagerFragmentArgs(
                    keyword = word,
                ).toBundle())
            }
        }
    }

    private fun checkAndNext(
        sender: ProgressIndicator,
        transform: (String) -> String = { it },
        block: suspend (String) -> Unit,
    ) {
        val inputBox = binding.inputBox
        launchSuspend(sender) {
            val word = transform(searchViewModel.inputDraft.value ?: "")
            if (word.trim().isNotEmpty()) {
                block(word)
            } else {
                if (alertYesOrCancel(getString(R.string.search_by_id_or_word))) {
                    delay(100L)
                    inputBox.requestFocusFromTouch()
                    showKeyboard(inputBox)
                }
            }
        }
    }

    // 粘贴来的 ID 可能夹杂表情/符号(防审核屏蔽);ID 搜索路径里只保留数字
    private fun checkAndNextId(sender: ProgressIndicator, block: suspend (Long) -> Unit) {
        checkAndNext(sender, transform = { it.filter(Char::isDigit) }) { digits ->
            block(digits.toLong())
        }
    }
}
