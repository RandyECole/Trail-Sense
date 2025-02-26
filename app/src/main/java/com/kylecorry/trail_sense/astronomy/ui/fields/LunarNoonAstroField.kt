package com.kylecorry.trail_sense.astronomy.ui.fields

import android.content.Context
import com.kylecorry.andromeda.alerts.Alerts
import com.kylecorry.andromeda.markdown.MarkdownService
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.shared.FormatService
import java.time.LocalTime

class LunarNoonAstroField(val time: LocalTime, val altitude: Float) : AstroFieldTemplate() {
    override fun getTitle(context: Context): String {
        return context.getString(R.string.lunar_noon)
    }

    override fun getValue(context: Context): String {
        return FormatService(context).formatTime(time, includeSeconds = false)
    }

    override fun getImage(context: Context): Int {
        return R.drawable.ic_moon
    }

    override fun onClick(context: Context) {
        val formatService = FormatService(context)
        val markdownService = MarkdownService(context)
        val text = context.getString(
            R.string.astro_dialog_noon,
            formatService.formatTime(time, false),
            formatService.formatDegrees(altitude)
        )

        Alerts.dialog(
            context,
            getTitle(context),
            markdownService.toMarkdown(text),
            cancelText = null
        )
    }
}