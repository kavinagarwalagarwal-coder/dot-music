package com.dotmusic.app

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Dot Phone: keypad, recents, contacts and stars, in the app's liquid-glass and dot-matrix look.
 * Every contact can be given a photo of your own, which becomes the spinning dot record on the
 * call screen.
 */
class DialerActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var pages: FrameLayout
    private lateinit var keypadPage: View
    private lateinit var recentsPage: View
    private lateinit var contactsPage: View
    private lateinit var starsPage: View
    private lateinit var display: TextView
    private lateinit var matched: TextView
    private lateinit var avatar: ImageView
    private lateinit var callButton: TextView
    private lateinit var defaultBanner: LinearLayout
    private lateinit var recentsList: LinearLayout
    private lateinit var contactsList: LinearLayout
    private lateinit var starsGrid: LinearLayout
    private lateinit var search: EditText
    private lateinit var badge: TextView
    private lateinit var pageTitle: TextView
    private lateinit var aura: AuraBackdrop
    private lateinit var dock: LinearLayout
    private lateinit var dockHolder: FrameLayout
    private val dockItems = ArrayList<LinearLayout>()
    private val dockIcons = ArrayList<ImageView>()
    private val dockLabels = ArrayList<TextView>()

    private val number = StringBuilder()
    private var page = PAGE_NONE
    private var lastBadge = -1
    private var groups: List<ContactGroup>? = null
    private var recents: List<Contacts.Recent>? = null
    private var nameToken = 0
    private var pendingCall: String? = null
    private var sheet: View? = null
    private var pickingFor: ContactGroup? = null
    private var activeMatch: ContactGroup? = null
    private var searchToken = 0
    private var bottomInset = 0
    private val systemPhotos = android.util.LruCache<String, Bitmap>(48)

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Float) = Glass.px(this, v)
    private fun enabled(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Glass.BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        setContentView(buildUi())
        showPage(PAGE_KEYPAD)
        readIntent(intent)
        if (!Store.onboarded(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        if (Store.missedNotifications(this)) MissedCalls.start(this)
        updateBadge()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        Glass.tapListener = { view -> if (::aura.isInitialized) aura.matrix.rippleFrom(view) }
        MissedCalls.appVisible = true
        if (::defaultBanner.isInitialized) {
            updateDefaultBanner()
            refreshCurrentPage()
        }
        updateBadge()
        if (MissedCalls.countNow > 0) MissedCalls.opened(this)
    }

    override fun onPause() {
        super.onPause()
        Glass.tapListener = null
    }

    override fun onStop() {
        super.onStop()
        MissedCalls.appVisible = false
    }

    /**
     * Every touch is handed to the dot grid before the views see it, so the dots follow the finger
     * across keys, rows and the dock alike.
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (::aura.isInitialized) aura.matrix.track(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        super.onDestroy()
        MissedCalls.stop(this)
    }

    override fun onBackPressed() {
        when {
            sheet != null -> closeSheet()
            page != PAGE_KEYPAD -> showPage(PAGE_KEYPAD)
            else -> super.onBackPressed()
        }
    }

    private fun readIntent(intent: Intent?) {
        val raw = intent?.data?.schemeSpecificPart ?: return
        if (raw.isBlank()) return
        number.setLength(0)
        number.append(raw)
        refreshNumber()
    }

    // ---------------------------------------------------------------- scaffolding

    private fun buildUi(): View {
        root = FrameLayout(this)
        aura = AuraBackdrop(this)
        root.addView(aura, FrameLayout.LayoutParams(-1, -1))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val cutout = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout?.safeInsetTop ?: 0 else 0
            @Suppress("DEPRECATION")
            val top = maxOf(insets.systemWindowInsetTop, cutout)
            @Suppress("DEPRECATION")
            bottomInset = insets.systemWindowInsetBottom
            content.setPadding(dp(18f), top + dp(8f), dp(18f), bottomInset + dp(68f))
            if (::dockHolder.isInitialized) dockHolder.setPadding(0, 0, 0, bottomInset + dp(14f))
            insets
        }

        content.addView(header(), LinearLayout.LayoutParams(-1, -2))

        keypadPage = buildKeypad()
        recentsPage = buildListPage { recentsList = it }
        contactsPage = buildContactsPage()
        starsPage = buildListPage { starsGrid = it }
        pages = FrameLayout(this)
        pages.addView(keypadPage, FrameLayout.LayoutParams(-1, -1))
        pages.addView(recentsPage, FrameLayout.LayoutParams(-1, -1))
        pages.addView(contactsPage, FrameLayout.LayoutParams(-1, -1))
        pages.addView(starsPage, FrameLayout.LayoutParams(-1, -1))
        content.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16f) })
        buildDock()
        return root
    }

    /**
     * Navigation lives at the bottom, the way the reference does it: one filled pill naming the
     * page you are on, a quiet circle for each of the others, floating over the list. Nothing sits
     * between the title and the content.
     */
    private fun buildDock() {
        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        dock.addView(dockItem(PAGE_KEYPAD, R.drawable.ic_dialpad, getString(R.string.tab_keypad)), dockSlot())
        dock.addView(dockItem(PAGE_RECENTS, R.drawable.ic_call_line, getString(R.string.tab_recents)), dockSlot())
        dock.addView(dockItem(PAGE_CONTACTS, R.drawable.ic_people, getString(R.string.tab_people)), dockSlot())
        dock.addView(dockItem(PAGE_STARS, R.drawable.ic_star_outline, getString(R.string.tab_stars)), dockSlot())
        dockHolder = FrameLayout(this).apply {
            setPadding(0, 0, 0, bottomInset + dp(14f))
            addView(dock, FrameLayout.LayoutParams(-1, dp(46f), Gravity.BOTTOM))
        }
        root.addView(dockHolder, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        updateDock(PAGE_KEYPAD, false)
        Motion.enter(dockHolder, 140, 18f)
    }

    private fun dockSlot() = LinearLayout.LayoutParams(-2, dp(46f)).apply {
        setMargins(dp(5f), 0, dp(5f), 0)
    }

    private fun dockItem(index: Int, icon: Int, name: String): LinearLayout {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = name
            Motion.press(this, 0.94f)
            setOnClickListener {
                Glass.tap(this)
                if (page != index) showPage(index)
            }
        }
        val glyph = ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(Glass.MUTED)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        item.addView(glyph, LinearLayout.LayoutParams(dp(21f), dp(21f)))
        val text = TextView(this).apply {
            text = name
            textSize = 13f
            typeface = Glass.medium(this@DialerActivity)
            setTextColor(Glass.INK)
            maxLines = 1
            setPadding(dp(8f), 0, 0, 0)
            visibility = View.GONE
        }
        item.addView(text)
        dockItems += item
        dockIcons += glyph
        dockLabels += text
        return item
    }

    /** The page you are on grows into a named pill; the rest fall back to a plain circle. */
    private fun updateDock(index: Int, animate: Boolean = true) {
        if (!::dock.isInitialized) return
        dockItems.forEachIndexed { i, item ->
            val on = i == index
            item.background = if (on) Glass.fill(this, Glass.blend(Glass.CARD, Color.WHITE, 0.16f), 999f)
            else Glass.panel(this, 999f)
            val side = dp(if (on) 18f else 13f)
            item.setPadding(side, 0, side, 0)
            dockIcons[i].setColorFilter(if (on) Glass.INK else Glass.MUTED)
            val text = dockLabels[i]
            text.visibility = if (on) View.VISIBLE else View.GONE
            item.animate().cancel()
            if (!animate) {
                text.alpha = 1f
                item.scaleX = 1f
                item.scaleY = 1f
                return@forEachIndexed
            }
            if (on) {
                text.alpha = 0f
                text.animate().alpha(1f).setDuration(200).start()
                item.scaleX = 0.94f
                item.scaleY = 0.94f
                item.animate().scaleX(1f).scaleY(1f)
                    .setDuration(240).setInterpolator(Motion.spring).start()
            }
        }
    }

    private fun header(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title.addView(Glass.text(this, "DOT", 9f, Glass.RED, dot = true, spacing = 0.22f).apply {
            setPadding(dp(2f), 0, 0, dp(4f))
        })
        pageTitle = Glass.display(this, getString(R.string.tab_keypad), 32f)
        title.addView(pageTitle)
        row.addView(title, LinearLayout.LayoutParams(0, -2, 1f))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        val settings = TextView(this).apply {
            text = "⋯"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Glass.INK)
            background = Glass.panel(this@DialerActivity, 999f)
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.settings_title)
            Motion.press(this, 0.93f)
            setOnClickListener {
                Glass.tap(this)
                startActivity(Intent(this@DialerActivity, SettingsActivity::class.java))
            }
        }
        right.addView(settings, LinearLayout.LayoutParams(dp(44f), dp(44f)))
        badge = TextView(this).apply {
            text = ""
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Glass.ON_RED)
            background = Glass.fill(this@DialerActivity, Glass.RED, 999f)
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.missed_title)
            Motion.press(this, 0.88f)
            setOnClickListener { Glass.tap(this); missedAction() }
        }
        right.addView(badge, LinearLayout.LayoutParams(dp(22f), dp(22f)).apply {
            setMargins(dp(6f), 0, 0, dp(10f))
        })
        row.addView(right, LinearLayout.LayoutParams(0, -2).apply { gravity = Gravity.BOTTOM })
        return row
    }

    private fun showPage(index: Int) {
        val changed = page != index
        page = index
        val titles = arrayOf(
            R.string.tab_keypad, R.string.tab_recents, R.string.tab_people, R.string.tab_stars
        )
        if (::pageTitle.isInitialized) {
            val next = getString(titles[index])
            if (pageTitle.text.toString() != next) {
                pageTitle.animate().cancel()
                pageTitle.animate().alpha(0f).setDuration(80).withEndAction {
                    pageTitle.text = next
                    pageTitle.alpha = 0f
                    pageTitle.translationY = dp(6f).toFloat()
                    pageTitle.animate().alpha(1f).translationY(0f)
                        .setDuration(220).setInterpolator(Motion.out).start()
                }.start()
            }
        }
        val all = listOf(keypadPage, recentsPage, contactsPage, starsPage)
        all.forEachIndexed { i, view -> view.visibility = if (i == index) View.VISIBLE else View.GONE }
        updateDock(index)
        val target = all[index]
        target.animate().cancel()
        if (changed) {
            target.alpha = 0f
            target.translationY = dp(10f).toFloat()
            target.animate().alpha(1f).translationY(0f)
                .setDuration(220).setInterpolator(Motion.out).start()
        } else {
            target.alpha = 1f
            target.translationY = 0f
        }
        refreshCurrentPage()
    }

    private fun refreshCurrentPage() = when (page) {
        PAGE_RECENTS -> fillRecents()
        PAGE_CONTACTS -> fillPeople(search.text.toString())
        PAGE_STARS -> fillStars()
        else -> Unit
    }

    // ---------------------------------------------------------------- keypad

    private fun buildKeypad(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Glass.card(this@DialerActivity, 20f)
            setPadding(dp(14f), dp(6f), dp(8f), dp(6f))
        }
        avatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        card.addView(avatar, LinearLayout.LayoutParams(dp(44f), dp(44f)).apply { rightMargin = dp(12f) })

        val stack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        display = Glass.text(this, getString(R.string.enter_number), 30f, Glass.DIM)
        display.typeface = Glass.light(this)
        display.maxLines = 1
        display.ellipsize = TextUtils.TruncateAt.START
        display.contentDescription = getString(R.string.phone_number)
        matched = Glass.text(this, "", 10f, Glass.RED, dot = true, spacing = 0.12f)
        matched.maxLines = 1
        matched.ellipsize = TextUtils.TruncateAt.END
        stack.addView(display)
        stack.addView(matched, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2f) })
        card.addView(stack, LinearLayout.LayoutParams(0, -2, 1f))

        val erase = TextView(this).apply {
            text = "⌫"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(Glass.MUTED)
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.delete_digit)
            Motion.press(this, 0.9f)
            setOnClickListener {
                if (number.isNotEmpty()) {
                    Glass.tap(this)
                    number.deleteCharAt(number.lastIndex)
                    refreshNumber()
                }
            }
            setOnLongClickListener {
                number.setLength(0)
                refreshNumber()
                true
            }
        }
        card.addView(erase, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        page.addView(card, LinearLayout.LayoutParams(-1, dp(78f)))

        page.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))

        defaultBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = Glass.card(this@DialerActivity, 16f)
            isClickable = true
            setOnClickListener { requestDialerRole() }
            setPadding(dp(12f), 0, dp(12f), 0)
        }
        defaultBanner.addView(Glass.section(this, getString(R.string.make_default), Glass.RED))
        page.addView(defaultBanner, LinearLayout.LayoutParams(-1, dp(40f)).apply { topMargin = dp(10f) })

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.sheet(this@DialerActivity, 28f)
            setPadding(dp(10f), dp(8f), dp(10f), dp(12f))
        }
        sheet.addView(Glass.grabber(this), LinearLayout.LayoutParams(dp(36f), dp(4f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(10f)
        })

        val keypad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val keys = listOf(
            listOf("1" to "", "2" to "ABC", "3" to "DEF"),
            listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
            listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
            listOf("*" to "", "0" to "+", "#" to "")
        )
        keys.forEach { rowKeys ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowKeys.forEach { (digit, letters) ->
                row.addView(key(digit, letters),
                    LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(3f), dp(3f), dp(3f), dp(3f)) })
            }
            keypad.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        sheet.addView(keypad, LinearLayout.LayoutParams(-1, 0, 1f))

        callButton = TextView(this).apply {
            text = getString(R.string.call)
            typeface = Glass.medium(this@DialerActivity)
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Glass.ON_RED)
            background = Glass.fill(this@DialerActivity, Glass.RED, 27f)
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.call)
            setOnClickListener { Glass.tap(this); dial(number.toString()) }
        }
        sheet.addView(callButton, LinearLayout.LayoutParams(-1, dp(54f)).apply { topMargin = dp(8f) })
        page.addView(sheet, LinearLayout.LayoutParams(-1, 0, 2.3f).apply { topMargin = dp(10f) })
        return page
    }

    private fun key(digit: String, letters: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = Glass.key(this@DialerActivity, 22f)
        isClickable = true
        isFocusable = true
        Motion.press(this, 0.94f)
        addView(Glass.text(this@DialerActivity, digit, 27f, Glass.INK).apply {
            gravity = Gravity.CENTER
            typeface = Glass.light(this@DialerActivity)
        })
        if (letters.isNotEmpty()) addView(Glass.text(this@DialerActivity, letters, 9f, Glass.MUTED, spacing = 0.08f).apply {
            gravity = Gravity.CENTER
        })
        if (digit == "1") addView(Glass.text(this@DialerActivity, "VM", 9f, Glass.MUTED, spacing = 0.08f).apply {
            gravity = Gravity.CENTER
        })
        setOnClickListener {
            if (digit == "1" && Store.speedDial(this@DialerActivity) != null) {
                Glass.tap(this)
                Toast.makeText(this@DialerActivity, R.string.speed_dial, Toast.LENGTH_SHORT).show()
                dialTarget(Store.speedDial(this@DialerActivity)!!)
            } else {
                tap(this, digit)
            }
        }
        if (digit == "1") setOnLongClickListener {
            Glass.tap(this)
            dialTarget("1")
            true
        }
        if (digit == "0") setOnLongClickListener { tap(this, "+"); true }
    }

    private fun tap(key: View, digit: String) {
        Glass.tap(key)
        number.append(digit)
        refreshNumber()
    }

    private fun refreshNumber() {
        val text = number.toString()
        display.text = if (text.isEmpty()) getString(R.string.enter_number) else text
        display.setTextColor(if (text.isEmpty()) Glass.DIM else Glass.INK)
        callButton.alpha = if (text.isEmpty()) 0.5f else 1f
        activeMatch = null
        showMatched("")
        avatar.visibility = View.GONE
        if (text.isNotEmpty()) lookupName(text)
    }

    /** The matched name eases in under the number instead of blinking into place. */
    private fun showMatched(label: String) {
        if (matched.text.toString() == label) return
        matched.text = label
        if (label.isEmpty()) return
        matched.animate().cancel()
        matched.alpha = 0f
        matched.translationY = dp(4f).toFloat()
        matched.animate().alpha(1f).translationY(0f)
            .setDuration(200).setInterpolator(Motion.out).start()
    }

    private fun lookupName(text: String) {
        val token = ++searchToken
        val digitsOnly = text.filter { it.isDigit() }
        if (digitsOnly.length >= 2 && digitsOnly.length <= 10 && digitsOnly == text) {
            t9Search(text, token)
        } else if (digitsOnly.isNotEmpty()) {
            fallbackNumberLookup(text, token)
        }
    }

    private fun showTypedAvatar(key: String) {
        val bitmap = ContactPhotos.avatar(this, key, dp(44f), Store.whiteDots(this)) ?: return
        avatar.setImageBitmap(bitmap)
        avatar.visibility = View.VISIBLE
    }

    private fun t9Of(name: String): String {
        val map = mapOf(
            'A' to '2', 'B' to '2', 'C' to '2',
            'D' to '3', 'E' to '3', 'F' to '3',
            'G' to '4', 'H' to '4', 'I' to '4',
            'J' to '5', 'K' to '5', 'L' to '5',
            'M' to '6', 'N' to '6', 'O' to '6',
            'P' to '7', 'Q' to '7', 'R' to '7', 'S' to '7',
            'T' to '8', 'U' to '8', 'V' to '8',
            'W' to '9', 'X' to '9', 'Y' to '9', 'Z' to '9'
        )
        return name.uppercase().map { c -> map[c] ?: "" }.joinToString("")
    }

    private fun t9Search(text: String, token: Int) {
        if (groups != null) { t9Match(text, token); return }
        Thread {
            val found = Contacts.contacts(this)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (searchToken != token) return@runOnUiThread
                groups = found
                t9Match(text, token)
            }
        }.start()
    }

    private fun t9Match(text: String, token: Int) {
        if (searchToken != token || isFinishing) return
        val match = groups?.asSequence()
            ?.map { group -> group to t9Of(group.name) }
            ?.firstOrNull { (_, nameT9) -> nameT9.startsWith(text) }
        if (match != null) {
            activeMatch = match.first
            showMatched(match.first.name.uppercase())
            showTypedAvatar(match.first.key)
        } else {
            activeMatch = null
            showMatched("")
            avatar.visibility = View.GONE
        }
    }

    private fun fallbackNumberLookup(text: String, token: Int) {
        val ftoken = token
        Thread {
            val found = Contacts.nameFor(this, text)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (searchToken != ftoken) return@runOnUiThread
                if (found != null) {
                    showMatched(found.uppercase())
                    showTypedAvatar(Store.key(text))
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- lists

    private fun buildListPage(assign: (LinearLayout) -> Unit): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        assign(list)
        scroll.addView(list, FrameLayout.LayoutParams(-1, -2))
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return page
    }

    private fun buildContactsPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        search = EditText(this).apply {
            hint = getString(R.string.search_contacts)
            setHintTextColor(Glass.DIM)
            setTextColor(Glass.INK)
            textSize = 14f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            background = Glass.panel(this@DialerActivity, 24f)
            setPadding(dp(18f), 0, dp(18f), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = renderPeople(s?.toString().orEmpty())
            })
        }
        page.addView(search, LinearLayout.LayoutParams(-1, dp(48f)))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        contactsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(contactsList, FrameLayout.LayoutParams(-1, -2))
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10f) })
        return page
    }

    private fun fillRecents() {
        recentsList.removeAllViews()
        if (!enabled(Manifest.permission.READ_CALL_LOG)) {
            recentsList.addView(prompt(getString(R.string.allow_history), getString(R.string.permission_note)) {
                requestPermissions(arrayOf(Manifest.permission.READ_CALL_LOG), REQ_RECENTS)
            })
            return
        }
        val cached = recents
        if (cached != null) { renderRecents(cached); return }
        Thread {
            val rows = Contacts.recents(this)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                recents = rows
                renderRecents(rows)
                if (rows.isEmpty()) recentsList.addView(empty(getString(R.string.no_calls)))
            }
        }.start()
    }

    private fun renderRecents(rows: List<Contacts.Recent>) {
        recentsList.removeAllViews()
        if (rows.isEmpty()) { recentsList.addView(empty(getString(R.string.no_calls))); return }
        val merged = rows.groupBy { it.key }.mapValues { it.value.first() }.toList()
            .sortedByDescending { it.second.date }
        val now = System.currentTimeMillis()
        val week = 7L * 24 * 60 * 60 * 1000
        val entered = ArrayList<View>()
        recentHalf(getString(R.string.section_recent), merged.filter { now - it.second.date < week }, rows, entered)
        recentHalf(getString(R.string.section_older), merged.filter { now - it.second.date >= week }, rows, entered)
        Motion.enterAll(entered.take(16))
    }

    /** One quiet label and its card, so the list reads in halves instead of one long pile. */
    private fun recentHalf(
        label: String,
        items: List<Pair<String, Contacts.Recent>>,
        rows: List<Contacts.Recent>,
        entered: ArrayList<View>
    ) {
        if (items.isEmpty()) return
        val heading = Glass.section(this, label).apply {
            setPadding(dp(6f), dp(4f), 0, dp(10f))
        }
        recentsList.addView(heading, LinearLayout.LayoutParams(-1, -2))
        entered += heading
        val cards = ArrayList<View>()
        items.forEach { (_, recent) ->
            val count = rows.count { it.key == recent.key }
            val missed = recent.type == android.provider.CallLog.Calls.MISSED_TYPE ||
                recent.type == android.provider.CallLog.Calls.REJECTED_TYPE
            val name = recent.name.ifBlank { recent.number }
            val detail = "${typeLabel(recent.type)}${if (count > 1) " · $count" else ""} · " +
                DateUtils.getRelativeTimeSpanString(
                    recent.date, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                )
            cards += row(
                avatarFor(recent.key, name, recent.photoUri, dp(44f)),
                name, detail,
                if (missed) Glass.RED else Glass.INK,
                onClick = { dial(recent.number) },
                onLong = { putNumber(recent.number) }
            )
        }
        grouped(cards, recentsList)
        entered += cards
    }

    private fun fillPeople(query: String) {        contactsList.removeAllViews()
        if (!enabled(Manifest.permission.READ_CONTACTS)) {
            contactsList.addView(prompt(getString(R.string.allow_contacts), getString(R.string.permission_note)) {
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
            })
            return
        }
        val cached = groups
        if (cached != null) { renderPeople(query); return }
        Thread {
            val found = Contacts.contacts(this)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                groups = found
                renderPeople(query)
            }
        }.start()
    }

    private fun renderPeople(query: String) {
        contactsList.removeAllViews()
        val all = groups ?: return
        val needle = query.trim()
        val digits = needle.filter { it.isDigit() }
        val shown = all.filter {
            needle.isEmpty() || it.name.contains(needle, true) ||
                (digits.isNotEmpty() && it.numbers.any { num -> num.filter { c -> c.isDigit() }.contains(digits) })
        }
        if (shown.isEmpty()) { contactsList.addView(empty(getString(R.string.no_contacts))); return }
        val cards = ArrayList<View>()
        shown.take(80).forEach { group ->
            val numbersText = group.numbers.distinct().joinToString(", ") { it }
            cards += row(
                avatarFor(group.key, group.name, group.photoUri, dp(44f)),
                group.name, numbersText, Glass.INK,
                onClick = { openGroup(group) },
                onLong = { dialTarget(group.mainNumber) }
            )
        }
        grouped(cards, contactsList)
        Motion.enterAll(cards.take(12))
    }

    private fun fillStars() {
        starsGrid.removeAllViews()
        if (!enabled(Manifest.permission.READ_CONTACTS)) {
            starsGrid.addView(prompt(getString(R.string.allow_contacts), getString(R.string.permission_note)) {
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
            })
            return
        }
        val cached = groups
        if (cached == null) {
            Thread {
                val found = Contacts.contacts(this)
                runOnUiThread { if (!isFinishing) { groups = found; fillStars() } }
            }.start()
            return
        }
        val starred = Store.starred(this)
        val favourites = cached.filter { starred.contains(it.key) }
        if (favourites.isEmpty()) {
            starsGrid.addView(empty(getString(R.string.no_stars)))
            return
        }
        val lines = ArrayList<View>()
        favourites.chunked(4).forEach { chunk ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { group ->
                line.addView(starCell(group), LinearLayout.LayoutParams(0, dp(104f), 1f).apply {
                    setMargins(dp(3f), dp(3f), dp(3f), dp(3f))
                })
            }
            repeat(4 - chunk.size) {
                line.addView(View(this), LinearLayout.LayoutParams(0, dp(104f), 1f).apply {
                    setMargins(dp(3f), dp(3f), dp(3f), dp(3f))
                })
            }
            lines += line
            starsGrid.addView(line, LinearLayout.LayoutParams(-1, -2))
        }
        Motion.enterAll(lines, 34, 12f)
    }

    private fun starCell(group: ContactGroup) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = Glass.panel(this@DialerActivity, 20f)
        isClickable = true
        isFocusable = true
        Motion.press(this, 0.95f)
        addView(ImageView(this@DialerActivity).apply {
            setImageBitmap(avatarFor(group.key, group.name, group.photoUri, dp(56f)))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(56f), dp(56f)))
        addView(Glass.text(this@DialerActivity, group.name.substringBefore(' '), 11.5f, Glass.MUTED).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
        setOnClickListener { openGroup(group) }
        setOnLongClickListener { dialTarget(group.mainNumber); true }
    }

    private fun row(
        leading: Bitmap,
        title: String,
        detail: String,
        titleColor: Int,
        onClick: () -> Unit,
        onLong: () -> Unit
    ): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Glass.row(this@DialerActivity)
            setPadding(dp(14f), dp(10f), dp(10f), dp(10f))
            minimumHeight = dp(66f)
            isClickable = true
            isFocusable = true
            setOnClickListener { Glass.tap(this); onClick() }
            setOnLongClickListener { Glass.tap(this); onLong(); true }
        }
        box.addView(ImageView(this).apply {
            setImageBitmap(leading)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(44f), dp(44f)).apply { rightMargin = dp(12f) })
        val stack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stack.addView(Glass.text(this@DialerActivity, title, 16f, titleColor).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        stack.addView(Glass.text(this@DialerActivity, detail, 12.5f, Glass.MUTED).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2f), 0, 0)
        })
        box.addView(stack, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_call_line)
            setColorFilter(Glass.MUTED)
            contentDescription = getString(R.string.call)
        }, LinearLayout.LayoutParams(dp(22f), dp(22f)).apply { leftMargin = dp(8f) })
        return box
    }

    /** Rows in one card, each with its own ripple and a hairline between them. */
    private fun grouped(rows: List<View>, list: LinearLayout) {
        val card = Glass.group(this)
        rows.forEachIndexed { i, view ->
            if (i > 0) card.addView(Glass.divider(this, dp(70f)))
            card.addView(view)
        }
        list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12f) })
    }

    private fun avatarFor(key: String, name: String, photoUri: String?, sizePx: Int): Bitmap {
        val mono = Store.whiteDots(this)
        ContactPhotos.avatar(this, key, sizePx, mono)?.let { return it }
        if (!photoUri.isNullOrBlank()) {
            val cacheKey = "$photoUri|$sizePx|$mono"
            systemPhotos.get(cacheKey)?.let { return it }
            ContactPhotos.decode(this, Uri.parse(photoUri))?.let { source ->
                val dots = ContactPhotos.dotsOf(source, sizePx, mono)
                systemPhotos.put(cacheKey, dots)
                return dots
            }
        }
        return initialAvatar(name.trim().firstOrNull()?.uppercase().orEmpty().ifEmpty { "#" }, sizePx, Glass.BLUE)
    }

    private fun initialAvatar(letter: String, sizePx: Int, tint: Int): Bitmap {
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
            color = Glass.withAlpha(Color.WHITE, 52)
        }
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx * 0.46f, ring)
        val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Glass.doto(this@DialerActivity)
            textSize = sizePx * 0.42f
            color = Glass.withAlpha(tint, 235)
            textAlign = Paint.Align.CENTER
        }
        val metrics = glyph.fontMetrics
        canvas.drawText(letter, sizePx / 2f, sizePx / 2f - (metrics.ascent + metrics.descent) / 2f, glyph)
        return out
    }

    private fun prompt(title: String, note: String, onAsk: () -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply {
            text = title
            typeface = Glass.medium(this@DialerActivity)
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Glass.INK)
            background = Glass.panel(this@DialerActivity, 18f)
            isClickable = true
            isFocusable = true
            setOnClickListener { onAsk() }
        }, LinearLayout.LayoutParams(-1, dp(52f)))
        box.addView(Glass.text(this, note, 12.5f, Glass.MUTED).apply {
            gravity = Gravity.CENTER
            setLineSpacing(dp(2f).toFloat(), 1f)
            setPadding(dp(10f), dp(12f), dp(10f), 0)
        })
        return box
    }

    private fun empty(text: String) = Glass.label(this, text, Glass.DIM).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(28f), 0, dp(28f))
    }

    private fun typeLabel(type: Int): String = when (type) {
        android.provider.CallLog.Calls.MISSED_TYPE -> getString(R.string.log_missed)
        android.provider.CallLog.Calls.OUTGOING_TYPE -> getString(R.string.log_outgoing)
        android.provider.CallLog.Calls.REJECTED_TYPE -> getString(R.string.log_declined)
        android.provider.CallLog.Calls.BLOCKED_TYPE -> getString(R.string.log_blocked)
        android.provider.CallLog.Calls.VOICEMAIL_TYPE -> getString(R.string.log_voicemail)
        else -> getString(R.string.log_incoming)
    }

    private fun putNumber(value: String) {
        number.setLength(0)
        number.append(value)
        refreshNumber()
        showPage(PAGE_KEYPAD)
    }

    // ---------------------------------------------------------------- the contact card

    private fun openGroup(group: ContactGroup) {
        closeSheet()
        activeMatch = null
        val dim = android.graphics.drawable.ColorDrawable(Color.argb(0, 0, 0, 0))
        val overlay = FrameLayout(this).apply {
            background = dim
            isClickable = true
            setOnClickListener { closeSheet() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.card(this@DialerActivity, 28f)
            clipToOutline = true
            setPadding(dp(14f), dp(14f), dp(14f), dp(16f))
            isClickable = true
            setOnClickListener { }
        }

        val record = DotRecord(this).apply {
            showInfo = false
            showTitle = false
            mono = Store.whiteDots(this@DialerActivity)
            accent = Glass.RED
            setTitle(group.name)
        }
        record.setArt(ContactPhotos.load(this, group.key), animate = false)
        card.addView(record, LinearLayout.LayoutParams(-1, dp(170f)))

        card.addView(Glass.display(this, group.name, 25f).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })
        card.addView(Glass.text(this, group.numbers.distinct().joinToString(", ") { it }, 13f, Glass.MUTED).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actions.addView(pill(getString(R.string.call), Glass.RED, Glass.ON_RED, filled = true) {
            dialTarget(group.mainNumber)
        }, LinearLayout.LayoutParams(0, dp(48f), 1f).apply { setMargins(dp(4f), dp(14f), dp(4f), 0) })
        actions.addView(pill(getString(R.string.message), Color.WHITE, Glass.INK) {
            message(group.mainNumber)
        }, LinearLayout.LayoutParams(0, dp(48f), 1f).apply { setMargins(dp(4f), dp(14f), dp(4f), 0) })
        card.addView(actions, LinearLayout.LayoutParams(-1, -2))

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val starred = Store.starred(this).contains(group.key)
        tools.addView(tool(if (starred) getString(R.string.unstar) else getString(R.string.star)) {
            Store.toggleStar(this@DialerActivity, group.key)
            openGroup(group)
        }, LinearLayout.LayoutParams(0, dp(44f), 1f).apply { setMargins(dp(4f), dp(10f), dp(4f), 0) })
        tools.addView(tool(getString(R.string.choose_photo)) {
            pickingFor = group
            val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            runCatching { startActivityForResult(Intent.createChooser(pick, getString(R.string.choose_photo)), REQ_PHOTO) }
        }, LinearLayout.LayoutParams(0, dp(44f), 1f).apply { setMargins(dp(4f), dp(10f), dp(4f), 0) })
        tools.addView(tool(getString(R.string.copy)) {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText(getString(R.string.phone_number), group.mainNumber))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, dp(44f), 1f).apply { setMargins(dp(4f), dp(10f), dp(4f), 0) })
        card.addView(tools, LinearLayout.LayoutParams(-1, -2))

        if (ContactPhotos.has(this, group.key)) {
            card.addView(tool(getString(R.string.remove_photo), Glass.RED) {
                ContactPhotos.clear(this@DialerActivity, group.key)
                openGroup(group)
            }, LinearLayout.LayoutParams(-1, dp(42f)).apply { setMargins(dp(4f), dp(8f), dp(4f), 0) })
        }

        overlay.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(14f), 0, dp(14f), bottomInset + dp(14f))
        })
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        android.animation.ValueAnimator.ofInt(0, 214).apply {
            duration = 240
            addUpdateListener { anim ->
                dim.alpha = (anim.animatedValue as Int).coerceIn(0, 255)
                overlay.invalidate()
            }
            start()
        }
        card.alpha = 0f
        card.translationY = dp(44f).toFloat()
        card.scaleX = 0.94f
        card.scaleY = 0.94f
        card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(300).setInterpolator(Motion.spring).start()
        sheet = overlay

        // The address-book photo is turned into dots off the main thread, then fades in.
        val photoUri = group.photoUri
        if (!ContactPhotos.has(this, group.key) && !photoUri.isNullOrBlank()) {
            Thread {
                val source = ContactPhotos.decode(this, Uri.parse(photoUri))
                runOnUiThread {
                    if (sheet === overlay && source != null) record.setArt(source)
                }
            }.start()
        }
    }

    private fun pill(label: String, fill: Int, ink: Int, filled: Boolean = false, action: () -> Unit) =
        TextView(this).apply {
            text = label
            typeface = Glass.medium(this@DialerActivity)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(ink)
            background = if (filled) Glass.fill(this@DialerActivity, fill, 26f)
            else Glass.panel(this@DialerActivity, 26f)
            isClickable = true
            isFocusable = true
            setOnClickListener { Glass.tap(this); action() }
        }

    private fun tool(label: String, tint: Int = Glass.INK, action: () -> Unit) =
        TextView(this).apply {
            text = label
            typeface = Glass.medium(this@DialerActivity)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(tint)
            background = Glass.panel(this@DialerActivity, 16f)
            isClickable = true
            isFocusable = true
            Motion.press(this, 0.96f)
            setOnClickListener { Glass.tap(this); action() }
        }

    private fun closeSheet() {
        val open = sheet ?: return
        sheet = null
        open.animate().alpha(0f).setDuration(160).withEndAction { root.removeView(open) }.start()
    }

    private fun updateBadge() {
        if (!::badge.isInitialized) return
        val n = MissedCalls.countNow
        val show = n > 0 && Store.missedNotifications(this) && Store.badgeEnabled(this)
        badge.visibility = if (show) View.VISIBLE else View.GONE
        badge.text = if (show) n.toString() else ""
        if (show && n != lastBadge) Motion.pop(badge)
        lastBadge = if (show) n else -1
    }

    private fun missedAction() {
        val number = MissedCalls.numberNow ?: return
        try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {}
        MissedCalls.clear(this)
        updateBadge()
    }

    private fun message(number: String) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, R.string.no_messaging, Toast.LENGTH_SHORT).show() }
    }

    @Deprecated("Platform result callback is enough for one photo picker.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PHOTO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val group = pickingFor ?: return
        val bitmap = ContactPhotos.decode(this, uri)
        if (bitmap == null) {
            Toast.makeText(this, R.string.photo_failed, Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val saved = ContactPhotos.save(this, group.key, bitmap)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (!saved) Toast.makeText(this, R.string.photo_failed, Toast.LENGTH_SHORT).show()
                refreshCurrentPage()
                openGroup(group)
            }
        }.start()
    }

    // ---------------------------------------------------------------- calling

    private fun isDefaultDialer(): Boolean =
        getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage == packageName

    private fun updateDefaultBanner() {
        defaultBanner.visibility = if (isDefaultDialer()) View.GONE else View.VISIBLE
    }

    private fun requestDialerRole() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roles = getSystemService(RoleManager::class.java)
                if (roles?.isRoleAvailable(RoleManager.ROLE_DIALER) == true) {
                    startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_DIALER), REQ_ROLE)
                }
            } else {
                startActivityForResult(
                    Intent(android.telecom.TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                        .putExtra(android.telecom.TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName),
                    REQ_ROLE
                )
            }
        } catch (_: Exception) {
            Toast.makeText(this, R.string.role_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun dialTarget(target: String) {
        val t = target.trim()
        if (t.isEmpty()) {
            Toast.makeText(this, R.string.need_number, Toast.LENGTH_SHORT).show()
            return
        }
        if (!enabled(Manifest.permission.CALL_PHONE)) {
            pendingCall = t
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), REQ_CALL)
            return
        }
        closeSheet()
        val uri = Uri.fromParts("tel", t, null)
        try {
            if (isDefaultDialer()) {
                val telecom = getSystemService(android.telecom.TelecomManager::class.java) ?: error("No telecom")
                telecom.placeCall(uri, Bundle())
            } else {
                startActivity(Intent(Intent.ACTION_CALL, uri))
            }
        } catch (_: Exception) {
            Toast.makeText(this, R.string.call_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun dial(digits: String) = dialTarget(activeMatch?.mainNumber ?: digits.trim())


    @Deprecated("Platform permission result callback is sufficient here.")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val allowed = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_CALL -> {
                val next = pendingCall
                pendingCall = null
                if (allowed && next != null) dial(next)
            }
            REQ_RECENTS -> if (allowed) { recents = null; fillRecents(); if (Store.missedNotifications(this)) MissedCalls.start(this) }
            REQ_CONTACTS -> if (allowed) { groups = null; refreshCurrentPage() }
        }
    }

    private companion object {
        const val PAGE_NONE = -1
        const val PAGE_KEYPAD = 0
        const val PAGE_RECENTS = 1
        const val PAGE_CONTACTS = 2
        const val PAGE_STARS = 3
        const val REQ_ROLE = 31
        const val REQ_CALL = 32
        const val REQ_CONTACTS = 33
        const val REQ_RECENTS = 34
        const val REQ_PHOTO = 35
    }
}
