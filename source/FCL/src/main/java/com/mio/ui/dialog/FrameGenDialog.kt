package com.mio.ui.dialog

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import com.mio.util.getScreenHeight
import com.mio.util.getScreenWidth
import com.tungsten.fcl.R
import com.tungsten.fcl.databinding.DialogFramegenBinding
import com.tungsten.fcllibrary.component.dialog.FCLDialog
import com.tungsten.fcllibrary.component.theme.ThemeEngine
import com.tungsten.fcllibrary.component.view.FCLButton
import com.tungsten.fcllibrary.util.ConvertUtils

/**
 * 插帧设置对话框。
 *
 * 信息层级（结论先行，机制下沉）：
 *   ① 状态卡：当前状态 + 生效路径 + 自动降级 + 生效时机
 *   ② 开关：启用插帧
 *   ③ 倍率：2× / 3× / 4× 胶囊三选一
 *
 * 布局与控件沿用 FCL 现有对话框（FCLDialog + FCLSwitch + FCLButton），
 * 卡片圆角取 12dp、间距取 4 的倍数，与设置页其余对话框一致。
 */
/** 供 Java 侧直接写 lambda（Kotlin 的 (Boolean, Int) -> Unit 在 Java 里返回 Unit，不方便） */
fun interface FrameGenConfirmListener {
    fun onConfirm(enabled: Boolean, multiplier: Int)
}

class FrameGenDialog(
    context: Context,
    enabled: Boolean,
    multiplier: Int,
    private val confirmListener: FrameGenConfirmListener,
) : FCLDialog(context) {

    private val binding = DialogFramegenBinding.inflate(layoutInflater)

    /** 次要文字色：不依赖 color 资源，跟着夜间模式走（亮 #6B6B6B / 暗 #B0B0B0） */
    private fun secondaryColor(): Int =
        (if (ThemeEngine.isNightMode(context)) "#B0B0B0" else "#6B6B6B").toColorInt()
    private val density = context.resources.displayMetrics.density
    private var multiplierValue: Int = multiplier.coerceIn(2, 4)

    init {
        val params = window?.attributes
        params?.width = ConvertUtils.dip2px(context, 500f)
        val ratio = getScreenWidth().toFloat() / getScreenHeight().toFloat()
        params?.height = if (ratio >= 1.5f) {
            WindowManager.LayoutParams.MATCH_PARENT
        } else {
            getScreenHeight() * 1 / 2
        }
        window?.attributes = params

        setContentView(binding.root)

        binding.title.setText(R.string.framegen_title)
        binding.enableLabel.setText(R.string.framegen_enable)
        binding.enableDesc.setText(R.string.framegen_enable_desc)
        binding.multiplierLabel.setText(R.string.framegen_multiplier)
        binding.multiplierDesc.setText(R.string.framegen_multiplier_desc)
        binding.pathText.setText(R.string.framegen_info_path)
        binding.fallbackText.setText(R.string.framegen_info_fallback)
        binding.whenText.setText(R.string.framegen_info_when)

        // 状态卡：12dp 圆角 + 与对话框分层可辨的底色（暗色深灰、亮色近白）
        binding.statusCard.background = GradientDrawable().apply {
            cornerRadius = 12 * density
            setColor(if (ThemeEngine.isNightMode(context)) "#323232".toColorInt() else "#F8F8F8".toColorInt())
        }

        binding.enableSwitch.isChecked = enabled
        binding.enableSwitch.setOnCheckedChangeListener { _, _ -> refreshState() }

        binding.mult2.setOnClickListener { multiplierValue = 2; refreshMultiplier() }
        binding.mult3.setOnClickListener { multiplierValue = 3; refreshMultiplier() }
        binding.mult4.setOnClickListener { multiplierValue = 4; refreshMultiplier() }

        binding.cancel.setOnClickListener { dismiss() }
        binding.ok.setOnClickListener {
            confirmListener.onConfirm(binding.enableSwitch.isChecked, multiplierValue)
            dismiss()
        }

        refreshMultiplier()
        refreshState()
    }

    /** 倍率胶囊：选中项用主题色描边并点亮文字，未选中项降透明度 */
    private fun refreshMultiplier() {
        val themeColor = ThemeEngine.getTheme().getColor()
        listOf(
            binding.mult2 to 2,
            binding.mult3 to 3,
            binding.mult4 to 4,
        ).forEach { (button: FCLButton, value: Int) ->
            val selected = value == multiplierValue
            button.alpha = if (selected) 1f else 0.55f
            val stroke = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 100 * density          // 胶囊
                setColor(ColorUtils.setAlphaComponent(themeColor, if (selected) 38 else 0))
                setStroke((if (selected) 2 else 1) * density.toInt(), themeColor)
            }
            button.background = stroke
            button.setTextColor(if (selected) themeColor else secondaryColor())
            button.gravity = Gravity.CENTER
            (button.layoutParams as? LinearLayout.LayoutParams)?.height = (40 * density).toInt()
        }
        refreshState()
    }

    /** 状态行：结论 + 倍率，一眼可见 */
    private fun refreshState() {
        val enabled = binding.enableSwitch.isChecked
        binding.stateText.text = if (enabled) {
            context.getString(R.string.framegen_state_on, multiplierValue)
        } else {
            context.getString(R.string.framegen_state_off)
        }
        binding.stateText.setTextColor(
            if (enabled) ThemeEngine.getTheme().getColor()
            else secondaryColor()
        )
        val alpha = if (enabled) 1f else 0.5f
        binding.mult2.alpha = alpha * (if (multiplierValue == 2) 1f else 0.55f)
        binding.mult3.alpha = alpha * (if (multiplierValue == 3) 1f else 0.55f)
        binding.mult4.alpha = alpha * (if (multiplierValue == 4) 1f else 0.55f)
    }
}
