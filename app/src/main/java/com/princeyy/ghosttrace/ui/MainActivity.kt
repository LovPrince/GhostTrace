package com.princeyy.ghosttrace.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.princeyy.ghosttrace.databinding.ActivityMainBinding
import com.princeyy.ghosttrace.model.AppRecord
import com.princeyy.ghosttrace.model.AppSource
import com.princeyy.ghosttrace.utils.FormatUtils
import com.princeyy.ghosttrace.utils.UninstalledAppTracker
import com.princeyy.ghosttrace.utils.ScanResult
import com.princeyy.ghosttrace.utils.UsageScanner
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: AppListAdapter
    private var scanResult: ScanResult? = null

    // Filter state
    private var filterUsed  = false
    private var sortByFg    = false
    private var gamesOnly   = false
    private var sourceFilter: AppSource? = null  // null = all sources

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupChipListeners()
        setupActionButtons()
        loadData()
    }

    private fun setupRecyclerView() {
        adapter = AppListAdapter()
        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = adapter
    }

    private fun setupChipListeners() {
        binding.chipAll.setOnClickListener {
            filterUsed = false; gamesOnly = false; sourceFilter = null
            updateChipStates(); applyFilters()
        }
        binding.chipUsed.setOnClickListener {
            filterUsed = true; gamesOnly = false; sourceFilter = null
            updateChipStates(); applyFilters()
        }
        binding.chipGames.setOnClickListener {
            gamesOnly = !gamesOnly; filterUsed = false
            updateChipStates(); applyFilters()
        }
        binding.chipSort.setOnClickListener {
            sortByFg = !sortByFg
            binding.chipSort.text = if (sortByFg) "Sort: FG time" else "Sort: Last active"
            applyFilters()
        }
        // Source filter chips
        binding.chipGhosts.setOnClickListener {
            sourceFilter = if (sourceFilter == AppSource.GHOST) null else AppSource.GHOST
            updateChipStates(); applyFilters()
        }
        binding.chipBg.setOnClickListener {
            sourceFilter = if (sourceFilter == AppSource.BACKGROUND) null else AppSource.BACKGROUND
            updateChipStates(); applyFilters()
        }
        binding.chipProc.setOnClickListener {
            sourceFilter = if (sourceFilter == AppSource.PROC_GHOST) null else AppSource.PROC_GHOST
            updateChipStates(); applyFilters()
        }
    }

    private fun updateChipStates() {
        binding.chipAll.isChecked    = !filterUsed && !gamesOnly && sourceFilter == null
        binding.chipUsed.isChecked   = filterUsed
        binding.chipGames.isChecked  = gamesOnly
        binding.chipGhosts.isChecked = sourceFilter == AppSource.GHOST
        binding.chipBg.isChecked     = sourceFilter == AppSource.BACKGROUND
        binding.chipProc.isChecked   = sourceFilter == AppSource.PROC_GHOST
    }

    private fun setupActionButtons() {
        binding.btnCopy.setOnLongClickListener {
            Thread {
                val dump = UninstalledAppTracker.dumpRawEvents(this)
                val file = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "ghosttrace_debug.txt")
                file.writeText(dump)
                runOnUiThread {
                    Toast.makeText(this, "Debug saved: ${file.absolutePath}",
                        Toast.LENGTH_LONG).show()
                }
            }.start()
            true
        }
        binding.btnCopy.setOnClickListener {
            val txt = FormatUtils.exportTxt(getFilteredList())
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("GhostTrace", txt))
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
        binding.btnSave.setOnClickListener {
            val txt = FormatUtils.exportTxt(getFilteredList())
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val fname = "ghosttrace_${sdf.format(Date())}.txt"
            val file = File(getExternalFilesDir(null), fname)
            file.writeText(txt)
            Toast.makeText(this, "Saved: $fname", Toast.LENGTH_SHORT).show()
        }
        binding.btnShare.setOnClickListener {
            val txt = FormatUtils.exportTxt(getFilteredList())
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val file = File(cacheDir, "ghosttrace_${sdf.format(Date())}.txt")
            file.writeText(txt)
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "GhostTrace Report — by @princeyy")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share via"))
        }
    }

    private fun loadData() {
        binding.tvScanStatus.text = "Scanning..."
        Thread {
            val result = UsageScanner.scan(this)
            scanResult = result
            runOnUiThread {
                updateStats(result)
                applyFilters()
                setupChart(result.all)
            }
        }.start()
    }

    private fun updateStats(result: ScanResult) {
        // Ghost count = all three categories
        binding.tvGhostCount.text = result.totalCount.toString()
        binding.tvTotalFg.text    = FormatUtils.totalFgFormatted(result.ghosts)
        val top = result.all.maxByOrNull { it.sessions }
        binding.tvTopSessions.text = top?.sessions?.toString() ?: "0"
        binding.tvTopPkg.text      = top?.packageName?.split(".")?.last() ?: "-"
        binding.tvLogWindow.text   = "${result.ghosts.size}👻 ${result.background.size}⚙ ${result.procGhosts.size}🔍"
        binding.tvScanStatus.text  = "${result.totalCount} packages found"
    }

    private fun applyFilters() {
        val list = getFilteredList()
        adapter.submitList(list)
        binding.tvListCount.text = "${list.size} result${if (list.size != 1) "s" else ""}"
    }

    private fun getFilteredList(): List<AppRecord> {
        val base = scanResult?.all ?: emptyList()
        var list = base
        if (filterUsed)        list = list.filter { it.fgMillis > 0 }
        if (gamesOnly)         list = list.filter { it.isGame }
        sourceFilter?.let { sf -> list = list.filter { it.source == sf } }
        list = if (sortByFg) list.sortedByDescending { it.fgMillis }
               else list.sortedByDescending { it.lastSeen }
        return list
    }

    private fun setupChart(records: List<AppRecord>) {
        val top = records.filter { it.fgMillis > 0 }
            .sortedByDescending { it.fgMillis }
            .take(6)
        if (top.isEmpty()) return

        val entries = top.mapIndexed { i, r -> BarEntry(i.toFloat(), r.fgMillis / 60000f) }
        val labels  = top.map { it.packageName.split(".").last().take(8) }
        val colors  = listOf(0xFF00E5FF, 0xFF7C3AED, 0xFF00E676,
                             0xFFFFD740, 0xFFFF6E40, 0xFFF06292)
                         .map { it.toInt() }

        val dataSet = BarDataSet(entries, "FG time (min)").apply {
            setColors(colors)
            valueTextColor = 0xFF6E7681.toInt()
            valueTextSize  = 9f
        }
        binding.barChart.apply {
            data = BarData(dataSet).apply { barWidth = 0.6f }
            description.isEnabled  = false
            legend.isEnabled       = false
            setBackgroundColor(0xFF111419.toInt())
            setDrawGridBackground(false)
            axisLeft.apply {
                textColor  = 0xFF6E7681.toInt()
                gridColor  = 0xFF1E2530.toInt()
                axisLineColor = 0xFF1E2530.toInt()
            }
            axisRight.isEnabled = false
            xAxis.apply {
                position      = XAxis.XAxisPosition.BOTTOM
                textColor     = 0xFF6E7681.toInt()
                gridColor     = 0xFF1E2530.toInt()
                axisLineColor = 0xFF1E2530.toInt()
                valueFormatter = IndexAxisValueFormatter(labels)
                granularity   = 1f
                labelCount    = labels.size
            }
            animateY(800)
            invalidate()
        }
    }
}
