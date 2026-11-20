package x.vladgba.files

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import x.vladgba.files.pui.PAboutInfo
import x.vladgba.files.pui.showPAbout

object SettingsDialog {

    fun showSettings(activity: MainActivity, onUpdated: () -> Unit) {
        val prefs = activity.getSharedPreferences("pfiles", Context.MODE_PRIVATE)
        fun dp(v: Int) = Ui.dp(activity, v)

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
        }

        // 1. Default apps
        container.addView(sectionHeader(activity, activity.getString(R.string.category_default_apps)))
        val defaultAppsSummary = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = 0.7f
        }
        fun refreshAppsSummary() {
            val count = Openers.getAllPreferred(activity).size
            defaultAppsSummary.text = if (count == 0) {
                activity.getString(R.string.default_apps_subtitle)
            } else {
                activity.resources.getQuantityString(R.plurals.items, count, count) + " " + activity.getString(R.string.default_apps).lowercase()
            }
        }
        refreshAppsSummary()

        val defaultAppsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(10))
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            addView(TextView(activity).apply {
                text = activity.getString(R.string.default_apps_title)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(activity.getColor(R.color.p_text))
            })
            addView(defaultAppsSummary)
            setOnClickListener {
                showDefaultApps(activity) {
                    refreshAppsSummary()
                    onUpdated()
                }
            }
        }
        container.addView(defaultAppsRow)

        // 2. File manager & Display
        container.addView(sectionHeader(activity, activity.getString(R.string.category_file_manager)))

        var showHidden = prefs.getBoolean("hidden", false)
        container.addView(checkRow(activity, activity.getString(R.string.show_hidden), null, showHidden) { checked ->
            showHidden = checked
            prefs.edit().putBoolean("hidden", showHidden).apply()
            onUpdated()
        })

        var dualMode = prefs.getBoolean("dual", false)
        container.addView(checkRow(activity, activity.getString(R.string.two_panels), null, dualMode) { checked ->
            dualMode = checked
            prefs.edit().putBoolean("dual", dualMode).apply()
            onUpdated()
        })

        val viewStyles = listOf(
            R.string.view_details,
            R.string.view_compact,
            R.string.view_grid,
            R.string.view_large_icons,
            R.string.view_table
        )
        var viewStyle = prefs.getInt("view_style", 0).coerceIn(0, viewStyles.size - 1)
        val viewStyleLabel = TextView(activity).apply {
            text = activity.getString(viewStyles[viewStyle])
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = 0.7f
        }
        val viewStyleRow = actionRow(activity, activity.getString(R.string.view_style), viewStyleLabel) {
            val names = viewStyles.map { activity.getString(it) }.toTypedArray()
            AlertDialog.Builder(activity)
                .setTitle(R.string.view_style)
                .setSingleChoiceItems(names, viewStyle) { d, which ->
                    viewStyle = which
                    prefs.edit().putInt("view_style", viewStyle).apply()
                    viewStyleLabel.text = activity.getString(viewStyles[viewStyle])
                    onUpdated()
                    d.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        container.addView(viewStyleRow)

        val sortModes = listOf(
            R.string.sort_name,
            R.string.sort_size,
            R.string.sort_date,
            R.string.sort_type
        )
        var sortMode = prefs.getInt("sort", 0).coerceIn(0, sortModes.size - 1)
        val sortModeLabel = TextView(activity).apply {
            text = activity.getString(sortModes[sortMode])
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = 0.7f
        }
        val sortModeRow = actionRow(activity, activity.getString(R.string.sort_by), sortModeLabel) {
            val names = sortModes.map { activity.getString(it) }.toTypedArray()
            AlertDialog.Builder(activity)
                .setTitle(R.string.sort)
                .setSingleChoiceItems(names, sortMode) { d, which ->
                    sortMode = which
                    prefs.edit().putInt("sort", sortMode).apply()
                    sortModeLabel.text = activity.getString(sortModes[sortMode])
                    onUpdated()
                    d.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        container.addView(sortModeRow)

        var sortDesc = prefs.getBoolean("desc", false)
        container.addView(checkRow(activity, activity.getString(R.string.descending), null, sortDesc) { checked ->
            sortDesc = checked
            prefs.edit().putBoolean("desc", sortDesc).apply()
            onUpdated()
        })

        // 3. Text & Code editor
        container.addView(sectionHeader(activity, activity.getString(R.string.category_editor)))

        var fontSp = prefs.getFloat("ed_font", 14f)
        val fontLabel = TextView(activity).apply {
            text = String.format("%d sp", fontSp.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12), 0, dp(12), 0)
        }
        val fontRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            val textLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                addView(TextView(activity).apply {
                    text = activity.getString(R.string.font_size, fontSp.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(activity.getColor(R.color.p_text))
                    tag = "font_title"
                })
            }
            val minusBtn = Button(activity).apply {
                text = "-"
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(40))
                setOnClickListener {
                    if (fontSp > 10f) {
                        fontSp -= 1f
                        prefs.edit().putFloat("ed_font", fontSp).apply()
                        fontLabel.text = String.format("%d sp", fontSp.toInt())
                        (textLayout.findViewWithTag<TextView>("font_title"))?.text = activity.getString(R.string.font_size, fontSp.toInt())
                    }
                }
            }
            val plusBtn = Button(activity).apply {
                text = "+"
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(40))
                setOnClickListener {
                    if (fontSp < 32f) {
                        fontSp += 1f
                        prefs.edit().putFloat("ed_font", fontSp).apply()
                        fontLabel.text = String.format("%d sp", fontSp.toInt())
                        (textLayout.findViewWithTag<TextView>("font_title"))?.text = activity.getString(R.string.font_size, fontSp.toInt())
                    }
                }
            }
            addView(textLayout)
            addView(minusBtn)
            addView(fontLabel)
            addView(plusBtn)
        }
        container.addView(fontRow)

        var wrap = prefs.getBoolean("ed_wrap", false)
        container.addView(checkRow(activity, activity.getString(R.string.word_wrap), null, wrap) { checked ->
            wrap = checked
            prefs.edit().putBoolean("ed_wrap", wrap).apply()
        })

        var lines = prefs.getBoolean("ed_lines", true)
        container.addView(checkRow(activity, activity.getString(R.string.line_numbers), null, lines) { checked ->
            lines = checked
            prefs.edit().putBoolean("ed_lines", lines).apply()
        })

        var highlight = prefs.getBoolean("hl", true)
        container.addView(checkRow(activity, activity.getString(R.string.syntax_highlighting), null, highlight) { checked ->
            highlight = checked
            prefs.edit().putBoolean("hl", highlight).apply()
        })

        var invis = prefs.getBoolean("invis", false)
        container.addView(checkRow(activity, activity.getString(R.string.show_invisibles), null, invis) { checked ->
            invis = checked
            prefs.edit().putBoolean("invis", invis).apply()
        })

        // 4. Markdown viewer
        container.addView(sectionHeader(activity, activity.getString(R.string.category_markdown)))

        var breaks = prefs.getBoolean("md_breaks", false)
        container.addView(checkRow(activity, activity.getString(R.string.md_breaks), null, breaks) { checked ->
            breaks = checked
            prefs.edit().putBoolean("md_breaks", breaks).apply()
        })

        var anchors = prefs.getBoolean("md_anchors", true)
        container.addView(checkRow(activity, activity.getString(R.string.md_anchors), null, anchors) { checked ->
            anchors = checked
            prefs.edit().putBoolean("md_anchors", anchors).apply()
        })

        // 5. System & Access
        container.addView(sectionHeader(activity, activity.getString(R.string.category_system)))

        val shizukuSubtitle = TextView(activity).apply {
            text = if (ShizukuFiles.available()) activity.getString(R.string.shizuku_enabled) else activity.getString(R.string.shizuku_disabled)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = 0.7f
        }
        val shizukuRow = actionRow(activity, activity.getString(R.string.shizuku_status), shizukuSubtitle) {
            ShizukuFiles.request(activity)
            shizukuSubtitle.text = if (ShizukuFiles.available()) activity.getString(R.string.shizuku_enabled) else activity.getString(R.string.shizuku_disabled)
        }
        container.addView(shizukuRow)

        val aboutSubtitle = TextView(activity).apply {
            text = activity.getString(R.string.about)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = 0.7f
        }
        val aboutRow = actionRow(activity, activity.getString(R.string.about), aboutSubtitle) {
            activity.showPAbout(PAboutInfo(repo = "pFiles"))
        }
        container.addView(aboutRow)

        val scroll = ScrollView(activity).apply { addView(container) }

        AlertDialog.Builder(activity)
            .setTitle(R.string.settings)
            .setView(scroll)
            .setPositiveButton(R.string.done) { _, _ -> onUpdated() }
            .show()
    }

    fun showDefaultApps(activity: Activity, onUpdated: (() -> Unit)? = null) {
        fun dp(v: Int) = Ui.dp(activity, v)

        var dialog: AlertDialog? = null

        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun populateList() {
            listContainer.removeAllViews()
            val all = Openers.getAllPreferred(activity).toSortedMap()

            val addBtn = Button(activity).apply {
                text = "+ " + activity.getString(R.string.add_extension)
                setOnClickListener {
                    Ui.input(
                        activity,
                        activity.getString(R.string.add_extension),
                        "",
                        InputType.TYPE_CLASS_TEXT,
                        R.string.done,
                        onPositive = { input ->
                            val ext = Openers.normalizeExt(input)
                            if (ext.isNotEmpty() && ext != "<none>") {
                                showAppPicker(activity, ext) {
                                    populateList()
                                    onUpdated?.invoke()
                                }
                            }
                        }
                    )
                }
            }
            listContainer.addView(addBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(12))
            })

            if (all.isEmpty()) {
                val emptyView = TextView(activity).apply {
                    text = activity.getString(R.string.no_default_apps)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, dp(16), 0, dp(16))
                    alpha = 0.7f
                }
                listContainer.addView(emptyView)
                dialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility = View.GONE
            } else {
                for ((ext, target) in all) {
                    val targetName = Openers.targetLabel(activity, target)
                    val row = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(6), 0, dp(6))
                    }

                    val infoLayout = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                        addView(TextView(activity).apply {
                            text = ".$ext"
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                            setTypeface(typeface, Typeface.BOLD)
                            setTextColor(activity.getColor(R.color.p_text))
                        })
                        addView(TextView(activity).apply {
                            text = targetName
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                            alpha = 0.7f
                        })
                    }

                    val changeBtn = Button(activity).apply {
                        text = activity.getString(R.string.change)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, dp(38)).apply {
                            setMargins(0, 0, dp(6), 0)
                        }
                        setOnClickListener {
                            showAppPicker(activity, ext) {
                                populateList()
                                onUpdated?.invoke()
                            }
                        }
                    }

                    val removeBtn = Button(activity).apply {
                        text = activity.getString(R.string.remove)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, dp(38))
                        setOnClickListener {
                            Openers.forget(activity, ext)
                            Toast.makeText(activity, activity.getString(R.string.default_app_removed, ext), Toast.LENGTH_SHORT).show()
                            populateList()
                            onUpdated?.invoke()
                        }
                    }

                    row.addView(infoLayout)
                    row.addView(changeBtn)
                    row.addView(removeBtn)
                    listContainer.addView(row)
                }
                dialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility = View.VISIBLE
            }
        }

        val scroll = ScrollView(activity).apply {
            setPadding(dp(20), dp(8), dp(20), dp(12))
            addView(listContainer)
        }

        populateList()

        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.default_apps_title)
            .setView(scroll)
            .setPositiveButton(R.string.done) { _, _ -> onUpdated?.invoke() }
            .setNeutralButton(R.string.clear_all) { _, _ ->
                AlertDialog.Builder(activity)
                    .setTitle(R.string.clear_all)
                    .setMessage(R.string.clear_all_confirm)
                    .setPositiveButton(R.string.clear_all) { _, _ ->
                        Openers.clearAllPreferred(activity)
                        populateList()
                        onUpdated?.invoke()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .create()

        dialog.show()
        if (Openers.getAllPreferred(activity).isEmpty()) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility = View.GONE
        }
    }

    fun showAppPicker(activity: Activity, ext: String, onChosen: () -> Unit) {
        val pm = activity.packageManager

        data class PickerItem(val label: String, val target: String)
        val items = mutableListOf<PickerItem>()

        // 1. Built-in modules
        items.add(PickerItem(activity.getString(R.string.text_editor), "module:text"))
        items.add(PickerItem(activity.getString(R.string.hex_editor), "module:hex"))
        items.add(PickerItem(activity.getString(R.string.markdown_viewer), "module:markdown"))
        items.add(PickerItem(activity.getString(R.string.rtf_preview), "module:rtf"))
        items.add(PickerItem(activity.getString(R.string.zip_preview), "module:zip"))
        items.add(PickerItem("File viewer", "module:viewer"))

        // 2. Compatible external apps
        val apps = Openers.queryAppsForExtension(activity, ext)
        for (app in apps) {
            val label = app.loadLabel(pm).toString()
            val comp = ComponentName(app.activityInfo.packageName, app.activityInfo.name)
            items.add(PickerItem(label, comp.flattenToString()))
        }

        val labels = items.map { it.label }.toTypedArray()

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.choose_app_for_ext, ext))
            .setItems(labels) { _, which ->
                val selected = items[which]
                Openers.setPreferredRaw(activity, ext, selected.target)
                Toast.makeText(
                    activity,
                    activity.getString(R.string.default_app_saved, ext, selected.label),
                    Toast.LENGTH_SHORT
                ).show()
                onChosen()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sectionHeader(ctx: Context, title: String): TextView = TextView(ctx).apply {
        text = title
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ctx.getColor(R.color.p_accent))
        setPadding(0, Ui.dp(ctx, 14), 0, Ui.dp(ctx, 4))
    }

    private fun actionRow(ctx: Context, title: String, subtitleView: TextView, onClick: () -> Unit): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6))
        isClickable = true
        isFocusable = true
        setBackgroundResource(android.R.drawable.list_selector_background)
        addView(TextView(ctx).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(ctx.getColor(R.color.p_text))
        })
        addView(subtitleView)
        setOnClickListener { onClick() }
    }

    private fun checkRow(ctx: Context, title: String, subtitle: String?, initial: Boolean, onChecked: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6))
        }
        val textLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(ctx.getColor(R.color.p_text))
            })
            if (subtitle != null) {
                addView(TextView(ctx).apply {
                    text = subtitle
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    alpha = 0.7f
                })
            }
        }
        val cb = CheckBox(ctx).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChecked(checked) }
        }
        row.addView(textLayout)
        row.addView(cb)
        row.setOnClickListener { cb.toggle() }
        return row
    }
}
