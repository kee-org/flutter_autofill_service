package com.keevault.flutter_autofill_service

import android.graphics.drawable.Icon
import android.widget.RemoteViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * Verifies how the helpers drive the RemoteViews API. (What the views look like needs a device; see the
 * example app.)
 */
class RemoteViewsHelperFillDialogTest {

    @Test
    fun unlockButtonUsesItsOwnLayoutAndSetsTextAndTintedIcon() {
        val tinted = mock(Icon::class.java)
        val untinted = mock(Icon::class.java)
        `when`(untinted.setTint(0xFF123456.toInt())).thenReturn(tinted)

        mockStatic(Icon::class.java).use { icons ->
            icons.`when`<Icon> { Icon.createWithResource(anyString(), anyInt()) }.thenReturn(untinted)

            mockConstruction(RemoteViews::class.java) { _, context ->
                assertEquals(listOf("com.example.host", R.layout.fill_dialog_unlock_item), context.arguments())
            }.use { construction ->
                val views = RemoteViewsHelper.fillDialogUnlock(
                    "com.example.host", "Fill with Test", R.drawable.ic_lock_24dp, 0xFF123456.toInt()
                )

                assertEquals(1, construction.constructed().size)
                assertSame(construction.constructed().first(), views)
                verify(views).setTextViewText(R.id.text, "Fill with Test")
                verify(views).setImageViewIcon(R.id.icon, tinted)
                icons.verify { Icon.createWithResource("com.example.host", R.drawable.ic_lock_24dp) }
            }
        }
    }

    @Test
    fun headerUsesItsOwnLayoutAndSetsText() {
        mockConstruction(RemoteViews::class.java) { _, context ->
            assertEquals(listOf("com.example.host", R.layout.fill_dialog_header), context.arguments())
        }.use { construction ->
            val views = RemoteViewsHelper.fillDialogHeader("com.example.host", "Tap below to sign in")

            assertEquals(1, construction.constructed().size)
            verify(views).setTextViewText(R.id.text, "Tap below to sign in")
        }
    }

    @Test
    fun existingDropdownViewsAreUnchanged() {
        mockStatic(Icon::class.java).use { icons ->
            icons.`when`<Icon> { Icon.createWithResource(anyString(), anyInt()) }.thenReturn(mock(Icon::class.java))
            mockConstruction(RemoteViews::class.java) { _, context ->
                assertEquals(listOf("com.example.host", R.layout.multidataset_service_list_item), context.arguments())
            }.use {
                RemoteViewsHelper.viewsWithAuth("com.example.host", "Unlock")
                assertEquals(1, it.constructed().size)
            }
        }
    }
}
