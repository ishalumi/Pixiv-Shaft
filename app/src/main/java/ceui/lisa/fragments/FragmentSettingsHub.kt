package ceui.lisa.fragments

import android.Manifest
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import ceui.lisa.R
import ceui.lisa.databinding.FragmentSettingsHubBinding
import ceui.lisa.utils.Common
import com.tbruyelle.rxpermissions3.RxPermissions

/**
 * 设置主页（两级设置的第一级）：MD3-E 分类列表 + 全量设置项搜索。
 * 具体设置项都在 SettingsCatalog 定义的分类页里。
 */
class FragmentSettingsHub : BaseFragment<FragmentSettingsHubBinding>() {

    override fun initLayout() {
        mLayoutID = R.layout.fragment_settings_hub
    }

    override fun initData() {
        baseBind.toolbar.setNavigationOnClickListener { mActivity.finish() }

        buildCategoryList()

        baseBind.searchClear.setOnClickListener { baseBind.searchInput.setText("") }
        baseBind.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onQueryChanged(s?.toString().orEmpty())
            }
        })

        // Android 10 以下：设置页涉及备份/恢复等文件读写，进入时先要存储权限（沿用旧设置页行为）。
        if (!Common.isAndroidQ()) {
            RxPermissions(this)
                .requestEachCombined(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .subscribe { permission ->
                    if (!permission.granted) {
                        Common.showToast(getString(R.string.access_denied))
                        finish()
                    }
                }
        }
    }

    private fun buildCategoryList() {
        val container = baseBind.categoryContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(mContext)
        val categories = SettingsCatalog.categories
        categories.forEachIndexed { index, category ->
            val row = inflater.inflate(R.layout.item_settings_category, container, false)
            row.setBackgroundResource(backgroundFor(index, categories.size))
            row.findViewById<ImageView>(R.id.category_icon).setImageResource(category.iconRes)
            row.findViewById<TextView>(R.id.category_title).text = getString(category.titleRes)
            row.findViewById<TextView>(R.id.category_subtitle).text = subtitleFor(category)
            row.setOnClickListener {
                SettingsCatalog.open(mContext, category)
            }
            container.addView(row)
        }
    }

    /** 分类行的子项预览：把该分类下前几个设置项标题串起来，同时兼当搜索提示。 */
    private fun subtitleFor(category: SettingsCatalog.Category): String {
        val lang = resources.configuration.locales[0].language
        val separator = if (lang == "zh" || lang == "ja") "、" else ", "
        return SettingsCatalog.entriesOf(category)
            .take(4)
            .joinToString(separator) { getString(it.titleRes) }
    }

    private fun onQueryChanged(query: String) {
        val q = query.trim()
        baseBind.searchClear.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
        if (q.isEmpty()) {
            baseBind.categoryContainer.visibility = View.VISIBLE
            baseBind.searchResultsContainer.visibility = View.GONE
            baseBind.searchEmpty.visibility = View.GONE
            return
        }
        val results = SettingsCatalog.search(mContext, q).take(MAX_RESULTS)
        baseBind.categoryContainer.visibility = View.GONE
        val container = baseBind.searchResultsContainer
        container.removeAllViews()
        if (results.isEmpty()) {
            container.visibility = View.GONE
            baseBind.searchEmpty.visibility = View.VISIBLE
            return
        }
        baseBind.searchEmpty.visibility = View.GONE
        container.visibility = View.VISIBLE
        val inflater = LayoutInflater.from(mContext)
        results.forEachIndexed { index, entry ->
            val row = inflater.inflate(R.layout.item_settings_search_result, container, false)
            row.setBackgroundResource(backgroundFor(index, results.size))
            row.findViewById<TextView>(R.id.result_title).text = getString(entry.titleRes)
            val path = StringBuilder(getString(entry.category.titleRes))
            if (entry.descRes != 0) {
                path.append(" · ").append(getString(entry.descRes))
            }
            row.findViewById<TextView>(R.id.result_path).text = path
            row.setOnClickListener {
                SettingsCatalog.open(mContext, entry.category, entry.idName)
            }
            container.addView(row)
        }
    }

    private fun backgroundFor(index: Int, total: Int): Int {
        return when {
            total == 1 -> R.drawable.bg_m3_row_single
            index == 0 -> R.drawable.bg_m3_row_top
            index == total - 1 -> R.drawable.bg_m3_row_bottom
            else -> R.drawable.bg_m3_row_mid
        }
    }

    companion object {
        private const val MAX_RESULTS = 30
    }
}
