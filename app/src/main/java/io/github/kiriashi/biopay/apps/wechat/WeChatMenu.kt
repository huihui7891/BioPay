/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.apps.wechat

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import io.github.kiriashi.biopay.core.log.ModuleLog
import io.github.kiriashi.biopay.core.util.findActivity
import io.github.kiriashi.biopay.runtime.AppRuntime
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** Inserts before popup measurement; menu data and cleanup belong to the UI thread. */
internal class WeChatMenu(private val state: AppRuntime) {
    @Volatile var ready = false
    private val schema by lazy {
        runCatching { resolve(state.app.classLoader) }.onFailure {
            ModuleLog.w(it) { "WeChat action menu structure unavailable" }
        }.getOrNull()
    }
    private data class Edit(val original: SparseArray<*>, val installed: SparseArray<Any>)
    private val edits = WeakHashMap<Any, Edit>()
    private var itemShape: ItemShape? = null

    private data class ItemShape(
        val wrapper: Constructor<*>, val item: Constructor<*>,
        val definition: Field, val id: Field, val label: Field
    ) {
        fun create(): Any = wrapper.newInstance(item.newInstance(ITEM_ID, LABEL, "", 0, 0))

        fun idOf(entry: Any): Int = id.getInt(definition.get(entry))

        fun matches(entry: Any): Boolean {
            val value = definition.get(entry) ?: return false
            return id.getInt(value) == ITEM_ID && label.get(value) == LABEL
        }
    }

    fun register(xposed: XposedInterface, id: String): XposedInterface.HookHandle? {
        val shape = schema ?: return null
        val method = when (id) {
            SHOW_ID -> shape.show
            CLICK_ID -> shape.click
            ROW_ID -> shape.row
            else -> return null
        }
        return xposed.hook(method).setId(id).intercept(interceptor(id))
    }

    fun interceptor(id: String): XposedInterface.Hooker = XposedInterface.Hooker { chain ->
        if (state.isClosed || !ready) return@Hooker chain.proceed()
        val shape = schema ?: return@Hooker chain.proceed()
        val owner = chain.thisObject
        if (id == ROW_ID) {
            val result = chain.proceed()
            runCatching {
                val menu = shape.adapterOwner.get(owner)
                val position = chain.args[0] as Int
                if (isModuleItem(shape.items.get(menu) as? SparseArray<*>, position) && result is ViewGroup) {
                    decorate(result)
                }
            }.onFailure { ModuleLog.w(it) { "WeChat menu icon failed" } }
            return@Hooker result
        }
        if (!shape.menuClass.isInstance(owner)) return@Hooker chain.proceed()
        if (id == SHOW_ID) {
            runCatching { install(owner!!, shape) }.onFailure {
                ModuleLog.w(it) { "WeChat action menu entry failed" }
            }
        } else if (id == CLICK_ID) {
            val ownItem = runCatching {
                isModuleItem(shape.items.get(owner) as? SparseArray<*>, chain.args[2] as Int)
            }.onFailure { ModuleLog.w(it) { "WeChat menu item lookup failed" } }.getOrDefault(false)
            if (ownItem) {
                // An injected ID must never reach WeChat's payment routing or click reports.
                runCatching {
                    val context = shape.context.get(owner) as Context
                    shape.dismiss.invoke(owner)
                    state.showSettings(context)
                }.onFailure { ModuleLog.w(it) { "WeChat action menu click failed" } }
                return@Hooker null
            }
        }
        chain.proceed()
    }

    private fun install(menu: Any, shape: Schema) {
        val context = shape.context.get(menu) as? Context ?: return
        val activity = context.findActivity() ?: return
        if (activity.javaClass.name != "com.tencent.mm.ui.LauncherUI" || activity.isFinishing || activity.isDestroyed) return
        val source = shape.items.get(menu) as? SparseArray<*> ?: return
        val previous = edits[menu]
        if (previous?.installed === source && (0 until source.size()).any { isModuleItem(source, it) }) return
        if (source.size() == 0) return

        val template = source.valueAt(0) ?: return
        val fields = itemShape ?: resolveItem(template).also { itemShape = it }
        val wrapper = fields.create()

        // Dynamic configuration may share the original map; only replace this menu's reference.
        val copy = SparseArray<Any>(source.size() + 1)
        var inserted = false
        for (index in 0 until source.size()) {
            val entry = source.valueAt(index) ?: continue
            if (isModuleEntry(entry)) continue
            copy.put(copy.size(), entry)
            if (!inserted && fields.idOf(entry) == PAYMENT_ITEM_ID) {
                copy.put(copy.size(), wrapper)
                inserted = true
            }
        }
        if (!inserted) copy.put(copy.size(), wrapper)
        shape.items.set(menu, copy)
        edits[menu] = Edit(source, copy)
        ModuleLog.d { "WeChat action menu entry installed" }
    }

    private fun isModuleItem(items: SparseArray<*>?, position: Int): Boolean =
        items?.get(position)?.let(::isModuleEntry) == true

    private fun isModuleEntry(entry: Any): Boolean = runCatching {
        itemShape?.matches(entry) == true
    }.getOrDefault(false)

    private fun resolveItem(template: Any): ItemShape {
        val definition = template.javaClass.declaredFields.single { field ->
            field.type.declaredConstructors.any { it.parameterTypes.contentEquals(ITEM_PARAMETERS) }
        }.apply { isAccessible = true }
        val itemClass = definition.type
        val constructor = itemClass.getDeclaredConstructor(*ITEM_PARAMETERS).apply { isAccessible = true }
        val probe = constructor.newInstance(ITEM_ID, LABEL, "", 0, 0)
        val fields = itemClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }
        val id = fields.single { it.type == Int::class.javaPrimitiveType && it.getInt(probe) == ITEM_ID }
        val label = fields.single { it.type == String::class.java && it.get(probe) == LABEL }
        val wrapper = template.javaClass.getDeclaredConstructor(itemClass).apply { isAccessible = true }
        return ItemShape(wrapper, constructor, definition, id, label)
    }

    private fun decorate(row: ViewGroup) {
        val title = findTitle(row) ?: return
        val icon = row.getChildAt(0) as? ImageView ?: return
        icon.setImageDrawable(MenuIcon(title.currentTextColor))
        icon.visibility = View.VISIBLE
        icon.contentDescription = null
    }

    private fun findTitle(view: View, depth: Int = 0): TextView? {
        if (view is TextView && view.text.toString() == LABEL) return view
        if (view !is ViewGroup || depth >= 5) return null
        for (index in 0 until view.childCount) {
            findTitle(view.getChildAt(index), depth + 1)?.let { return it }
        }
        return null
    }

    fun close() {
        ready = false
        if (edits.isEmpty()) {
            itemShape = null
            return
        }
        val shape = schema ?: return
        edits.entries.toList().forEach { (menu, edit) ->
            runCatching {
                if (shape.items.get(menu) === edit.installed) {
                    try {
                        shape.dismiss.invoke(menu)
                    } finally {
                        if (shape.items.get(menu) === edit.installed) shape.items.set(menu, edit.original)
                    }
                }
            }.onFailure { ModuleLog.w(it) { "WeChat action menu cleanup failed" } }
        }
        edits.clear()
        itemShape = null
    }

    private data class Schema(
        val menuClass: Class<*>, val items: Field, val context: Field, val adapterOwner: Field,
        val show: Method, val dismiss: Method, val click: Method, val row: Method
    )

    private fun resolve(loader: ClassLoader): Schema {
        val home = loader.loadClass("com.tencent.mm.ui.HomeUI")
        val menu = home.declaredFields.map { it.type }.distinct().single { type ->
            AdapterView.OnItemClickListener::class.java.isAssignableFrom(type) &&
                type.declaredFields.any { SparseArray::class.java.isAssignableFrom(it.type) } &&
                type.declaredFields.any { BaseAdapter::class.java.isAssignableFrom(it.type) }
        }
        val items = menu.declaredFields.single { SparseArray::class.java.isAssignableFrom(it.type) }
        val context = menu.declaredFields.single { Context::class.java.isAssignableFrom(it.type) }
        val adapter = menu.declaredFields.single { BaseAdapter::class.java.isAssignableFrom(it.type) }.type
        val owner = adapter.declaredFields.single { it.type == menu }
        val base = menu.superclass!!
        val show = base.declaredMethods.single {
            it.returnType == Boolean::class.javaPrimitiveType && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }
        val dismiss = base.getDeclaredMethod("a")
        check(dismiss.returnType == Void.TYPE && !Modifier.isStatic(dismiss.modifiers))
        val click = menu.getDeclaredMethod("onItemClick", AdapterView::class.java, View::class.java, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
        val row = adapter.getDeclaredMethod("getView", Int::class.javaPrimitiveType, View::class.java, ViewGroup::class.java)
        listOf(items, context, owner).forEach { it.isAccessible = true }
        listOf(show, dismiss, click, row).forEach { it.isAccessible = true }
        return Schema(menu, items, context, owner, show, dismiss, click, row)
    }

    companion object {
        const val SHOW_ID = "bp_wechat_menu_show"
        const val CLICK_ID = "bp_wechat_menu_click"
        const val ROW_ID = "bp_wechat_menu_row"
        private const val ITEM_ID = -16976
        private const val PAYMENT_ITEM_ID = 20
        private const val LABEL = "生物支付"
        private val ITEM_PARAMETERS = arrayOf(Int::class.javaPrimitiveType, String::class.java, String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    }
}

private class MenuIcon(color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        val size = minOf(bounds.width(), bounds.height()) * 0.9f
        canvas.translate(bounds.exactCenterX() - size / 2f, bounds.exactCenterY() - size / 2f)
        canvas.scale(size / 24f, size / 24f)
        canvas.drawPath(shape, paint)
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        private val shape = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRoundRect(3f, 5.8f, 21f, 18.2f, 2f, 2f, Path.Direction.CW)
            addRoundRect(5.2f, 9.1f, 8.8f, 12f, 0.6f, 0.6f, Path.Direction.CW)
            addRoundRect(5.2f, 14.5f, 10.2f, 15.7f, 0.6f, 0.6f, Path.Direction.CW)

            moveTo(15.3f, 7.9f)
            cubicTo(14.3f, 7.9f, 13.5f, 8.7f, 13.5f, 9.7f)
            cubicTo(13.5f, 10.4f, 13.9f, 11f, 14.4f, 11.3f)
            lineTo(14.4f, 11.8f)
            cubicTo(13f, 12.2f, 12f, 13f, 12f, 14.5f)
            quadTo(12f, 15.7f, 13.1f, 15.7f)
            lineTo(17.5f, 15.7f)
            quadTo(18.6f, 15.7f, 18.6f, 14.5f)
            cubicTo(18.6f, 13f, 17.6f, 12.2f, 16.2f, 11.8f)
            lineTo(16.2f, 11.3f)
            cubicTo(16.7f, 11f, 17.1f, 10.4f, 17.1f, 9.7f)
            cubicTo(17.1f, 8.7f, 16.3f, 7.9f, 15.3f, 7.9f)
            close()
        }
    }
}
