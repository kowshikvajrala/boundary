package neth.iecal.curbox.ui.views

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import neth.iecal.curbox.R
import java.util.Locale

object ColorPickerDialog {

    fun show(
        context: Context,
        title: String,
        initialColor: Int,
        onColorSelected: (Int) -> Unit
    ) {
        val content = LayoutInflater.from(context).inflate(R.layout.dialog_color_picker, null)
        val colorWheel = content.findViewById<ColorWheelView>(R.id.colorWheel)
        val brightnessSlider = content.findViewById<Slider>(R.id.sliderColorBrightness)
        val hexInputLayout = content.findViewById<TextInputLayout>(R.id.hexInputLayout)
        val hexInput = content.findViewById<TextInputEditText>(R.id.etHexColor)
        val colorPreview = content.findViewById<MaterialCardView>(R.id.colorPreview)

        val selectedColor = initialColor and 0xFFFFFF
        var isUpdating = false

        fun colorHex(color: Int): String = String.format(Locale.ROOT, "%06X", color and 0xFFFFFF)

        fun updatePreview(color: Int) {
            val opaqueColor = color and 0xFFFFFF
            colorPreview.setCardBackgroundColor(Color.rgb(
                (opaqueColor shr 16) and 0xFF,
                (opaqueColor shr 8) and 0xFF,
                opaqueColor and 0xFF
            ))
        }

        fun updateHex(color: Int) {
            val value = colorHex(color)
            if (hexInput.text?.toString() != value) {
                isUpdating = true
                hexInput.setText(value)
                hexInput.setSelection(value.length)
                isUpdating = false
            }
            hexInputLayout.error = null
        }

        colorWheel.setColor(selectedColor)
        brightnessSlider.value = colorWheel.getBrightness() * 100f
        hexInput.setText(colorHex(selectedColor))
        updatePreview(selectedColor)

        colorWheel.onColorChanged = { color ->
            if (!isUpdating) {
                updatePreview(color)
                updateHex(color)
            }
        }

        brightnessSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isUpdating) {
                colorWheel.setBrightness(value / 100f)
            }
        }

        hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (isUpdating) return
                val value = s?.toString().orEmpty()
                if (value.length != 6) {
                    hexInputLayout.error = if (value.isEmpty()) null else {
                        context.getString(R.string.invalid_hex_color)
                    }
                    return
                }

                val color = value.toIntOrNull(16)
                if (color == null) {
                    hexInputLayout.error = context.getString(R.string.invalid_hex_color)
                    return
                }

                hexInputLayout.error = null
                isUpdating = true
                colorWheel.setColor(color)
                brightnessSlider.value = colorWheel.getBrightness() * 100f
                updatePreview(color)
                isUpdating = false
            }
        })

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = hexInput.text?.toString().orEmpty()
                val color = value.takeIf { it.length == 6 }?.toIntOrNull(16)
                if (color == null) {
                    hexInputLayout.error = context.getString(R.string.invalid_hex_color)
                } else {
                    onColorSelected(color and 0xFFFFFF)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
}
