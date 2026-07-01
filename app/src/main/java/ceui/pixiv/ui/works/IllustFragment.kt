package ceui.pixiv.ui.works

import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade
import com.bumptech.glide.request.RequestOptions.bitmapTransform
import jp.wasabeef.glide.transformations.BlurTransformation


fun Fragment.blurBackground(binding: FragmentPixivListBinding, illustId: Long) {
    val illust = ObjectPool.get<Illust>(illustId).value ?: return
    binding.dimmer.isVisible = true
    Glide.with(this)
        .load(GlideUrlChild(illust.image_urls?.large))
        .apply(bitmapTransform(BlurTransformation(15, 3)))
        .transition(withCrossFade())
        .into(binding.pageBackground)
}
