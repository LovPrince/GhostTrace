package com.princeyy.ghosttrace.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.princeyy.ghosttrace.R
import com.princeyy.ghosttrace.databinding.ItemAppBinding
import com.princeyy.ghosttrace.model.AppRecord
import com.princeyy.ghosttrace.model.AppSource
import com.princeyy.ghosttrace.utils.FormatUtils

class AppListAdapter : ListAdapter<AppRecord, AppListAdapter.VH>(DIFF) {

    private val expandedSet = mutableSetOf<String>()

    inner class VH(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val record = getItem(position)
        val b = holder.binding
        val isExpanded = expandedSet.contains(record.packageName)

        // Header
        b.tvPkgName.text = record.packageName
        b.tvAppLabel.text = record.appLabel
        b.tvFgTime.text = FormatUtils.formatMs(record.fgMillis)
        b.tvSessions.text = "${record.sessions} sessions"

        // Badges
        b.badgeGame.visibility = if (record.isGame) View.VISIBLE else View.GONE
        b.badgeSys.visibility = if (record.isSystem) View.VISIBLE else View.GONE

        // Source badge
        when (record.source) {
            AppSource.GHOST      -> { b.badgeSource.text = "GHOST";  b.badgeSource.visibility = View.VISIBLE }
            AppSource.BACKGROUND -> { b.badgeSource.text = "BG RUN"; b.badgeSource.visibility = View.VISIBLE }
            AppSource.PROC_GHOST -> { b.badgeSource.text = "PROC";   b.badgeSource.visibility = View.VISIBLE }
        }

        // Running-now indicator
        b.tvRunningNow.visibility = if (record.isRunningNow) View.VISIBLE else View.GONE
        if (record.serviceCount > 0) {
            b.tvRunningNow.text = "● LIVE · ${record.serviceCount} service${if (record.serviceCount > 1) "s" else ""}"
        } else if (record.isRunningNow) {
            b.tvRunningNow.text = "● RUNNING IN /proc"
        }

        // FG bar
        val maxFg = currentList.maxOfOrNull { it.fgMillis }?.takeIf { it > 0 } ?: 1L
        val pct = (record.fgMillis.toFloat() / maxFg * 100).toInt()
        b.fgBarFill.layoutParams = b.fgBarFill.layoutParams.apply {
            (this as ViewGroup.LayoutParams)
        }
        // Use weight-based progress via post
        b.fgBarFill.post {
            val parentWidth = b.fgBarBg.width
            val fillWidth = (parentWidth * pct / 100)
            b.fgBarFill.layoutParams = b.fgBarFill.layoutParams.also {
                it.width = fillWidth
            }
        }

        // Expand/collapse detail
        b.detailSection.visibility = if (isExpanded) View.VISIBLE else View.GONE
        b.ivChevron.rotation = if (isExpanded) 180f else 0f

        if (isExpanded) {
            b.tvDetailFirst.text = FormatUtils.formatDate(record.firstSeen)
            b.tvDetailLast.text = FormatUtils.formatDate(record.lastSeen)
            b.tvDetailFg.text = FormatUtils.formatMs(record.fgMillis)
            b.tvDetailSessions.text = record.sessions.toString()

            // Build timeline rows
            b.timelineContainer.removeAllViews()
            if (record.dailySessions.isNotEmpty()) {
                val maxSessions = record.dailySessions.values.maxOrNull() ?: 1
                record.dailySessions.entries
                    .sortedBy { it.key }
                    .forEach { (day, count) ->
                        val row = LayoutInflater.from(b.root.context)
                            .inflate(R.layout.item_timeline_row, b.timelineContainer, false)
                        val tvDay = row.findViewById<android.widget.TextView>(R.id.tvDay)
                        val barFill = row.findViewById<View>(R.id.tlBarFill)
                        val tvCount = row.findViewById<android.widget.TextView>(R.id.tvCount)
                        tvDay.text = day.substring(5) // "MM-dd"
                        tvCount.text = "${count}x"
                        barFill.post {
                            val pw = barFill.parent as ViewGroup
                            val w = (pw.width * count.toFloat() / maxSessions).toInt()
                            barFill.layoutParams = barFill.layoutParams.also { it.width = w }
                        }
                        b.timelineContainer.addView(row)
                    }
            }
        }

        b.root.setOnClickListener {
            if (isExpanded) expandedSet.remove(record.packageName)
            else expandedSet.add(record.packageName)
            notifyItemChanged(position)
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<AppRecord>() {
            override fun areItemsTheSame(a: AppRecord, b: AppRecord) = a.packageName == b.packageName
            override fun areContentsTheSame(a: AppRecord, b: AppRecord) = a == b
        }
    }
}
