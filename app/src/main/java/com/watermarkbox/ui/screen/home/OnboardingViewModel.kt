package com.watermarkbox.ui.screen.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watermarkbox.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 新手引导的显示状态。
 *
 * ## 为什么单独一个 ViewModel，而不是塞进 HomeScreen
 *
 * [HomeScreen] 是**纯 UI**：所有动作都通过回调交给上层，自己不做任何 I/O ——
 * 这样它才能在 `@Preview` 里正常渲染。引导状态要读 DataStore，
 * 塞进去会让预览直接崩（Preview 没有 ViewModelStoreOwner）。
 *
 * 放在这里、由导航层持有，职责也更清楚：
 * 「该不该显示引导」是**应用级**状态，不属于首页本身。
 */
class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    init {
        viewModelScope.launch {
            // 只在初始化时读一次：本次会话内不再变，
            // 用户完成后由 complete() 直接改状态，不必再查库
            _visible.value = !repository.isOnboardingDone()
        }
    }

    /** 用户看完或跳过 —— 记下来，下次不再显示。 */
    fun complete() {
        _visible.value = false
        viewModelScope.launch { repository.setOnboardingDone() }
    }
}