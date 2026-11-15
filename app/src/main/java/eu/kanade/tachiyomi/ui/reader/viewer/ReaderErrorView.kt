package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.core.view.isVisible
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.reader.loader.HttpPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.withUIContext
import karasu.i18n.MR
import karasu.util.lang.getString
import kotlinx.coroutines.CoroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import uy.kohesive.injekt.injectLazy

class ReaderErrorView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {
    lateinit var binding: ReaderErrorBinding
        private set

    private val networkHelper: NetworkHelper by injectLazy()

    var viewer: PagerViewer? = null
        set(value) {
            field = value
            binding.actionRetry.viewer = viewer
            binding.actionOpenInWebView.viewer = viewer
            binding.actionTryOtherSource.viewer = viewer
        }

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = ReaderErrorBinding.bind(this)
    }

    /**
     * @param page the page that failed, or null when the whole chapter did. Only a failed page can
     *   be moved to another source, since the switch works by repointing the pages already loaded.
     * @param scope the holder's scope — the switch is a network round trip and outlives nothing.
     */
    fun configureView(url: String?, page: ReaderPage? = null, scope: CoroutineScope? = null): ReaderErrorBinding {
        configureSessionReset(url, page)
        if (url?.startsWith("http", true) == true) {
            binding.actionOpenInWebView.isVisible = true
            binding.actionOpenInWebView.setOnClickListener {
                context.startActivity(WebViewActivity.newIntent(context, url))
            }
        } else {
            binding.actionOpenInWebView.isVisible = false
        }
        configureSourceSwitch(page, scope)
        binding.root.isVisible = true
        return binding
    }

    private fun configureSessionReset(url: String?, page: ReaderPage?) {
        val shouldOfferReset = page?.httpErrorCode in setOf(403, 503)
        binding.actionResetSession.isVisible = shouldOfferReset
        if (!shouldOfferReset) return

        binding.actionResetSession.setOnClickListener {
            val sessionUrl = (page?.imageUrl ?: url)?.toHttpUrlOrNull() ?: return@setOnClickListener
            networkHelper.perSourceSessionManager.clearSessionForUrl(sessionUrl)
            page?.httpErrorCode = null
            page?.let { it.chapter.pageLoader?.retryPage(it) }
            binding.actionResetSession.isVisible = false
        }
    }

    /**
     * Offers to read this chapter from another source.
     *
     * The automatic fallback only fires when a page throws, which leaves the case the reader can't
     * detect: the source answers fine and serves the wrong thing — pages out of order, a watermark
     * over everything, a scan too bad to read. Hidden unless the manga is merged, because on an
     * unmerged one the button would never have anywhere to go.
     */
    private fun configureSourceSwitch(page: ReaderPage?, scope: CoroutineScope?) {
        val loader = page?.chapter?.pageLoader as? HttpPageLoader
        binding.actionTryOtherSource.isVisible = loader?.canSwitchSource == true && scope != null
        if (loader == null || scope == null) return

        binding.actionTryOtherSource.setOnClickListener {
            scope.launchIO {
                val next = loader.switchSourceManually(page)
                withUIContext {
                    context.toast(
                        when (next) {
                            null -> context.getString(MR.strings.no_other_source_available)
                            else -> context.getString(MR.strings.now_reading_from, next.name)
                        },
                    )
                }
            }
        }
    }
}
