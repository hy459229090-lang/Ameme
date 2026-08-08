package com.ameme.android.ui.icons

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.ameme.android.R

object AmemeSymbols {
    val Add: ImageVector @Composable get() = symbol(R.drawable.symbol_add_24)
    val ArrowBack: ImageVector @Composable get() = symbol(R.drawable.symbol_arrow_back_24)
    val CalendarMonth: ImageVector @Composable get() = symbol(R.drawable.symbol_calendar_month_24)
    val CheckCircle: ImageVector @Composable get() = symbol(R.drawable.symbol_check_circle_24)
    val ChevronRight: ImageVector @Composable get() = symbol(R.drawable.symbol_chevron_right_24)
    val Close: ImageVector @Composable get() = symbol(R.drawable.symbol_close_24)
    val CloudOff: ImageVector @Composable get() = symbol(R.drawable.symbol_cloud_off_24)
    val Delete: ImageVector @Composable get() = symbol(R.drawable.symbol_delete_24)
    val DeleteForever: ImageVector @Composable get() = symbol(R.drawable.symbol_delete_forever_24)
    val Description: ImageVector @Composable get() = symbol(R.drawable.symbol_description_24)
    val EditNote: ImageVector @Composable get() = symbol(R.drawable.symbol_edit_note_24)
    val Error: ImageVector @Composable get() = symbol(R.drawable.symbol_error_24)
    val HourglassTop: ImageVector @Composable get() = symbol(R.drawable.symbol_hourglass_top_24)
    val Lock: ImageVector @Composable get() = symbol(R.drawable.symbol_lock_24)
    val MicNone: ImageVector @Composable get() = symbol(R.drawable.symbol_mic_none_24)
    val MoreVert: ImageVector @Composable get() = symbol(R.drawable.symbol_more_vert_24)
    val PhotoCamera: ImageVector @Composable get() = symbol(R.drawable.symbol_photo_camera_24)
    val PhotoLibrary: ImageVector @Composable get() = symbol(R.drawable.symbol_photo_library_24)
    val PlayCircle: ImageVector @Composable get() = symbol(R.drawable.symbol_play_circle_24)
    val Search: ImageVector @Composable get() = symbol(R.drawable.symbol_search_24)
    val Settings: ImageVector @Composable get() = symbol(R.drawable.symbol_settings_24)
    val SyncProblem: ImageVector @Composable get() = symbol(R.drawable.symbol_sync_problem_24)

    @Composable
    private fun symbol(@DrawableRes resourceId: Int): ImageVector =
        ImageVector.vectorResource(resourceId)
}
