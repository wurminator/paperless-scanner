@file:SuppressLint("RestrictedApi")

package com.paperless.scanner.widget

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.paperless.scanner.MainActivity
import com.paperless.scanner.R

// ==================== Material You Color Support ====================

/**
 * Holds resolved [ColorProvider] values for every color slot used by the widget.
 *
 * On Android 12+ (API 31) the colours are pulled from the platform
 * `android.R.color.system_*` dynamic-color palette so the widget matches the
 * user's wallpaper / Material You theme automatically.
 *
 * On older versions the classic dark-tech lime-green palette is used as-is
 * (via XML colour resources).
 */
data class WidgetColors(
    val background: ColorProvider,
    val surface: ColorProvider,
    val textPrimary: ColorProvider,
    val textSecondary: ColorProvider,
    val border: ColorProvider,
    val iconTint: ColorProvider,
    val badgeBackground: ColorProvider,
)

/**
 * Resolve the best available colour palette for the given [context].
 *
 * * API 31+ → Material You system dynamic colours
 * * API <31 → hardcoded dark-tech fallback (lime green accent)
 */
@Composable
private fun rememberWidgetColors(): WidgetColors {
    val context = LocalContext.current

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        WidgetColors(
            background   = ColorProvider(context.getColor(android.R.color.system_neutral1_800)),
            surface      = ColorProvider(context.getColor(android.R.color.system_neutral1_700)),
            textPrimary  = ColorProvider(context.getColor(android.R.color.system_accent1_200)),
            textSecondary = ColorProvider(context.getColor(android.R.color.system_neutral2_300)),
            border       = ColorProvider(context.getColor(android.R.color.system_neutral1_600)),
            iconTint     = ColorProvider(context.getColor(android.R.color.system_accent1_200)),
            badgeBackground = ColorProvider(context.getColor(android.R.color.system_neutral1_700)),
        )
    } else {
        WidgetColors(
            background      = ColorProvider(R.color.widget_background),
            surface         = ColorProvider(R.color.widget_surface),
            textPrimary     = ColorProvider(R.color.widget_text_primary),
            textSecondary   = ColorProvider(R.color.widget_text_secondary),
            border          = ColorProvider(R.color.widget_border),
            iconTint        = ColorProvider(R.color.widget_icon_tint),
            badgeBackground = ColorProvider(R.color.widget_badge_background),
        )
    }
}

// ==================== Glance Widget ====================

class ScannerWidget : GlanceAppWidget() {

    companion object {
        val PENDING_COUNT_KEY = intPreferencesKey("pending_upload_count")
        val SERVER_ONLINE_KEY = booleanPreferencesKey("server_online")
        val WIDGET_TYPE_KEY = stringPreferencesKey("widget_type")

        // Layout selection thresholds (used with SizeMode.Exact for real dimensions)
        internal val HORIZONTAL = DpSize(160.dp, 40.dp)   // 3x1 strip
        internal val SQUARE = DpSize(100.dp, 100.dp)       // 2x2 grid
        internal val LARGE = DpSize(160.dp, 100.dp)        // 3x2+ expanded
    }

    // SizeMode.Exact provides actual widget dimensions via LocalSize.current
    // (SizeMode.Responsive returned breakpoint sizes, causing height miscalculations)
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = (id as? AppWidgetId)?.appWidgetId
        Log.d("ScannerWidget", "provideGlance: glanceId=$id (${id::class.simpleName}), appWidgetId=$appWidgetId")

        // Sync widget type from SharedPreferences → Glance state
        // This ensures Glance state reflects the persisted config on every provideGlance call.
        if (appWidgetId != null) {
            val prefs = WidgetPreferences(context.applicationContext)
            val config = prefs.getWidgetConfig(appWidgetId)
            Log.d("ScannerWidget", "Syncing config to Glance state: id=$appWidgetId, type=${config.type}")
            updateAppWidgetState(context, id) { glancePrefs ->
                glancePrefs[WIDGET_TYPE_KEY] = config.type.name
            }
        }

        provideContent {
            // Read widget type from Glance state (reactive - triggers recomposition on change)
            val widgetTypeStr = currentState(key = WIDGET_TYPE_KEY)
            val widgetType = try {
                widgetTypeStr?.let { WidgetType.valueOf(it) } ?: WidgetType.QUICK_SCAN
            } catch (e: IllegalArgumentException) {
                WidgetType.QUICK_SCAN
            }

            val size = LocalSize.current
            val isTall = size.height >= SQUARE.height
            val isWide = size.width >= LARGE.width && isTall

            Log.d("ScannerWidget", "Rendering: type=$widgetType, size=${size.width}x${size.height}, isTall=$isTall, isWide=$isWide")

            when (widgetType) {
                WidgetType.QUICK_SCAN -> when {
                    isWide -> QuickScanWideContent()
                    isTall -> QuickScanSquareContent()
                    else -> QuickScanHorizontalContent()
                }
                WidgetType.STATUS -> when {
                    isTall -> StatusContent()
                    else -> StatusHorizontalContent()
                }
                WidgetType.COMBINED -> when {
                    isTall -> CombinedContent()
                    else -> CombinedHorizontalContent()
                }
            }
        }
    }

    // ==================== Quick Scan Layouts ====================

    /**
     * Quick Scan HORIZONTAL layout (3x1 strip).
     * 3 compact icon buttons in a single row, no labels.
     * Layout: [ Camera ] [ Gallery ] [ File ]
     */
    @Composable
    private fun QuickScanHorizontalContent() {
        val context = LocalContext.current
        val colors = rememberWidgetColors()

        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(16.dp)
                .background(colors.background),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // defaultWeight() is a RowScope function → equal 1/3 width per child
            CompactActionButton(
                icon = R.drawable.ic_widget_camera,
                label = context.getString(R.string.widget_action_camera),
                deepLinkUri = "paperless://scan/camera",
                modifier = GlanceModifier.defaultWeight(),
                colors = colors
            )
            Spacer(modifier = GlanceModifier.width(2.dp))
            CompactActionButton(
                icon = R.drawable.ic_widget_gallery,
                label = context.getString(R.string.widget_action_gallery),
                deepLinkUri = "paperless://scan/gallery",
                modifier = GlanceModifier.defaultWeight(),
                colors = colors
            )
            Spacer(modifier = GlanceModifier.width(2.dp))
            CompactActionButton(
                icon = R.drawable.ic_widget_file,
                label = context.getString(R.string.widget_action_file),
                deepLinkUri = "paperless://scan/file",
                modifier = GlanceModifier.defaultWeight(),
                colors = colors
            )
        }
    }

    /**
     * Quick Scan SQUARE layout (2x2 grid).
     * 2+1 grid with labels and large icons (36dp).
     * Layout:
     *   [ Camera ] [ Gallery ]
     *        [ File ]
     *
     * Height is calculated explicitly because fillMaxHeight() doesn't exist in Glance.
     * Each row gets half the available height after subtracting padding and spacers.
     */
    @Composable
    private fun QuickScanSquareContent() {
        val context = LocalContext.current
        val colors = rememberWidgetColors()
        val size = LocalSize.current
        // Each row: (total height - outer padding 4*2 - spacer 4) / 2 rows
        val cellHeight = (size.height - 12.dp) / 2

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(20.dp)
                .background(colors.background)
                .padding(4.dp)
        ) {
            // Top row: Camera + Gallery
            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(cellHeight),
                verticalAlignment = Alignment.CenterVertically
            ) {
                QuickActionButton(
                    icon = R.drawable.ic_widget_camera,
                    label = context.getString(R.string.widget_action_camera),
                    deepLinkUri = "paperless://scan/camera",
                    modifier = GlanceModifier.defaultWeight().height(cellHeight),
                    colors = colors
                )
                Spacer(modifier = GlanceModifier.width(4.dp))
                QuickActionButton(
                    icon = R.drawable.ic_widget_gallery,
                    label = context.getString(R.string.widget_action_gallery),
                    deepLinkUri = "paperless://scan/gallery",
                    modifier = GlanceModifier.defaultWeight().height(cellHeight),
                    colors = colors
                )
            }

            Spacer(modifier = GlanceModifier.height(4.dp))

            // Bottom row: File (full width)
            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(cellHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically
            ) {
                QuickActionButton(
                    icon = R.drawable.ic_widget_file,
                    label = context.getString(R.string.widget_action_file),
                    deepLinkUri = "paperless://scan/file",
                    modifier = GlanceModifier.defaultWeight().height(cellHeight),
                    colors = colors
                )
            }
        }
    }

    /**
     * Quick Scan WIDE layout (3x2+).
     * 3 action buttons with labels in a single row, larger spacing.
     * Layout: [ Camera ] [ Gallery ] [ File ]
     *
     * Height is calculated explicitly because fillMaxHeight() doesn't exist in Glance.
     * Each button fills the full available height.
     */
    @Composable
    private fun QuickScanWideContent() {
        val context = LocalContext.current
        val colors = rememberWidgetColors()
        val size = LocalSize.current
        // Full available height: total - outer padding (4*2)
        val cellHeight = size.height - 8.dp

        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(20.dp)
                .background(colors.background)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuickActionButton(
                icon = R.drawable.ic_widget_camera,
                label = context.getString(R.string.widget_action_camera),
                deepLinkUri = "paperless://scan/camera",
                modifier = GlanceModifier.defaultWeight().height(cellHeight),
                colors = colors
            )
            Spacer(modifier = GlanceModifier.width(4.dp))
            QuickActionButton(
                icon = R.drawable.ic_widget_gallery,
                label = context.getString(R.string.widget_action_gallery),
                deepLinkUri = "paperless://scan/gallery",
                modifier = GlanceModifier.defaultWeight().height(cellHeight),
                colors = colors
            )
            Spacer(modifier = GlanceModifier.width(4.dp))
            QuickActionButton(
                icon = R.drawable.ic_widget_file,
                label = context.getString(R.string.widget_action_file),
                deepLinkUri = "paperless://scan/file",
                modifier = GlanceModifier.defaultWeight().height(cellHeight),
                colors = colors
            )
        }
    }

    // ==================== Status Layouts ====================

    /**
     * Status widget content showing pending upload count and server connectivity.
     * Tapping opens the app to the SyncCenter screen.
     */
    @Composable
    private fun StatusContent() {
        val context = LocalContext.current
        val colors = rememberWidgetColors()
        val pendingCount = currentState(key = PENDING_COUNT_KEY) ?: 0
        val isOnline = currentState(key = SERVER_ONLINE_KEY) ?: false

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(20.dp)
                .background(colors.background)
                .clickable(
                    actionStartActivity(
                        createDeepLinkIntent(context, "paperless://status")
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Title
                Text(
                    text = "PAPERLESS",
                    style = TextStyle(
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                )

                Spacer(modifier = GlanceModifier.height(10.dp))

                // Pending uploads row
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .cornerRadius(8.dp)
                        .background(colors.surface)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_cloud_upload),
                        contentDescription = null,
                        modifier = GlanceModifier.size(18.dp),
                        colorFilter = ColorFilter.tint(colors.iconTint)
                    )
                    Spacer(modifier = GlanceModifier.width(8.dp))
                    Text(
                        text = if (pendingCount > 0) {
                            context.getString(R.string.widget_pending_format, pendingCount)
                        } else {
                            context.getString(R.string.widget_no_pending)
                        },
                        style = TextStyle(
                            color = colors.textSecondary,
                            fontSize = 12.sp
                        )
                    )
                }

                Spacer(modifier = GlanceModifier.height(6.dp))

                // Server status row
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .cornerRadius(8.dp)
                        .background(colors.surface)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        provider = ImageProvider(
                            if (isOnline) R.drawable.ic_widget_status_online
                            else R.drawable.ic_widget_status_offline
                        ),
                        contentDescription = null,
                        modifier = GlanceModifier.size(18.dp)
                    )
                    Spacer(modifier = GlanceModifier.width(8.dp))
                    Text(
                        text = context.getString(
                            if (isOnline) R.string.widget_status_online
                            else R.string.widget_status_offline
                        ),
                        style = TextStyle(
                            color = colors.textSecondary,
                            fontSize = 12.sp
                        )
                    )
                }
            }
        }
    }

    /**
     * Status HORIZONTAL layout (3x1 strip).
     * Compact bar: [upload icon + count] | [status icon + text]
     */
    @Composable
    private fun StatusHorizontalContent() {
        val context = LocalContext.current
        val colors = rememberWidgetColors()
        val pendingCount = currentState(key = PENDING_COUNT_KEY) ?: 0
        val isOnline = currentState(key = SERVER_ONLINE_KEY) ?: false

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(16.dp)
                .background(colors.background)
                .clickable(
                    actionStartActivity(
                        createDeepLinkIntent(context, "paperless://status")
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_cloud_upload),
                    contentDescription = null,
                    modifier = GlanceModifier.size(18.dp),
                    colorFilter = ColorFilter.tint(colors.iconTint)
                )
                Spacer(modifier = GlanceModifier.width(4.dp))
                Text(
                    text = if (pendingCount > 0) "$pendingCount" else "0",
                    style = TextStyle(
                        color = colors.textSecondary,
                        fontSize = 12.sp
                    )
                )

                Spacer(modifier = GlanceModifier.width(12.dp))

                Box(
                    modifier = GlanceModifier
                        .width(1.dp)
                        .height(20.dp)
                        .background(colors.border)
                ) {}

                Spacer(modifier = GlanceModifier.width(12.dp))

                Image(
                    provider = ImageProvider(
                        if (isOnline) R.drawable.ic_widget_status_online
                        else R.drawable.ic_widget_status_offline
                    ),
                    contentDescription = null,
                    modifier = GlanceModifier.size(18.dp)
                )
                Spacer(modifier = GlanceModifier.width(4.dp))
                Text(
                    text = context.getString(
                        if (isOnline) R.string.widget_status_online
                        else R.string.widget_status_offline
                    ),
                    style = TextStyle(
                        color = colors.textSecondary,
                        fontSize = 11.sp
                    )
                )
            }
        }
    }

    // ==================== Combined Layouts ====================

    /**
     * Combined widget: Quick actions (Camera + Gallery) on top, status bar on bottom.
     * Top row buttons launch deep links, bottom row opens SyncCenter.
     */
    @Composable
    private fun CombinedContent() {
        val context = LocalContext.current
        val colors = rememberWidgetColors()
        val pendingCount = currentState(key = PENDING_COUNT_KEY) ?: 0
        val isOnline = currentState(key = SERVER_ONLINE_KEY) ?: false

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(20.dp)
                .background(colors.background),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top: Quick action buttons (takes remaining space naturally)
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QuickActionButton(
                        icon = R.drawable.ic_widget_camera,
                        label = context.getString(R.string.widget_action_camera),
                        deepLinkUri = "paperless://scan/camera",
                        colors = colors
                    )
                    Spacer(modifier = GlanceModifier.width(6.dp))
                    QuickActionButton(
                        icon = R.drawable.ic_widget_gallery,
                        label = context.getString(R.string.widget_action_gallery),
                        deepLinkUri = "paperless://scan/gallery",
                        colors = colors
                    )
                }

                Spacer(modifier = GlanceModifier.height(4.dp))

                // Divider
                Box(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.border)
                ) {}

                Spacer(modifier = GlanceModifier.height(4.dp))

                // Bottom: Status bar (clickable → SyncCenter)
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .cornerRadius(8.dp)
                        .background(colors.surface)
                        .padding(vertical = 4.dp, horizontal = 8.dp)
                        .clickable(
                            actionStartActivity(
                                createDeepLinkIntent(context, "paperless://status")
                            )
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_cloud_upload),
                        contentDescription = null,
                        modifier = GlanceModifier.size(14.dp),
                        colorFilter = ColorFilter.tint(colors.iconTint)
                    )
                    Spacer(modifier = GlanceModifier.width(4.dp))
                    Text(
                        text = if (pendingCount > 0) {
                            context.getString(R.string.widget_pending_format, pendingCount)
                        } else {
                            context.getString(R.string.widget_no_pending)
                        },
                        style = TextStyle(
                            color = colors.textSecondary,
                            fontSize = 10.sp
                        )
                    )
                    Spacer(modifier = GlanceModifier.width(10.dp))
                    Image(
                        provider = ImageProvider(
                            if (isOnline) R.drawable.ic_widget_status_online
                            else R.drawable.ic_widget_status_offline
                        ),
                        contentDescription = null,
                        modifier = GlanceModifier.size(14.dp)
                    )
                }
            }
        }
    }

    /**
     * Combined HORIZONTAL layout (3x1 strip).
     * Compact: [Camera] [Gallery] | [pending + status]
     */
    @Composable
    private fun CombinedHorizontalContent() {
        val context = LocalContext.current
        val colors = rememberWidgetColors()
        val pendingCount = currentState(key = PENDING_COUNT_KEY) ?: 0
        val isOnline = currentState(key = SERVER_ONLINE_KEY) ?: false

        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(16.dp)
                .background(colors.background),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactActionButton(
                icon = R.drawable.ic_widget_camera,
                label = context.getString(R.string.widget_action_camera),
                deepLinkUri = "paperless://scan/camera",
                modifier = GlanceModifier.defaultWeight(),
                colors = colors
            )
            Spacer(modifier = GlanceModifier.width(2.dp))
            CompactActionButton(
                icon = R.drawable.ic_widget_gallery,
                label = context.getString(R.string.widget_action_gallery),
                deepLinkUri = "paperless://scan/gallery",
                modifier = GlanceModifier.defaultWeight(),
                colors = colors
            )

            Spacer(modifier = GlanceModifier.width(2.dp))

            Box(
                modifier = GlanceModifier
                    .width(1.dp)
                    .height(24.dp)
                    .background(colors.border)
            ) {}

            Spacer(modifier = GlanceModifier.width(2.dp))

            // Compact status (clickable → SyncCenter)
            Row(
                modifier = GlanceModifier
                    .defaultWeight()
                    .cornerRadius(10.dp)
                    .background(colors.surface)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clickable(
                        actionStartActivity(
                            createDeepLinkIntent(context, "paperless://status")
                        )
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_cloud_upload),
                    contentDescription = null,
                    modifier = GlanceModifier.size(14.dp),
                    colorFilter = ColorFilter.tint(colors.iconTint)
                )
                Spacer(modifier = GlanceModifier.width(3.dp))
                Text(
                    text = "$pendingCount",
                    style = TextStyle(
                        color = colors.textSecondary,
                        fontSize = 10.sp
                    )
                )
                Spacer(modifier = GlanceModifier.width(6.dp))
                Image(
                    provider = ImageProvider(
                        if (isOnline) R.drawable.ic_widget_status_online
                        else R.drawable.ic_widget_status_offline
                    ),
                    contentDescription = null,
                    modifier = GlanceModifier.size(14.dp)
                )
            }
        }
    }

    // ==================== Shared Button Components ====================

    /**
     * Action button with icon + label for square/wide layouts.
     * Width/height controlled by caller via modifier (defaultWeight() from Row/ColumnScope).
     * IMPORTANT: Do NOT use fillMaxSize() - it overrides the caller's width constraint.
     */
    @Composable
    private fun QuickActionButton(
        icon: Int,
        label: String,
        deepLinkUri: String,
        modifier: GlanceModifier = GlanceModifier,
        colors: WidgetColors = rememberWidgetColors()
    ) {
        val context = LocalContext.current
        Column(
            modifier = modifier
                .cornerRadius(12.dp)
                .background(colors.surface)
                .padding(4.dp)
                .clickable(
                    actionStartActivity(
                        createDeepLinkIntent(context, deepLinkUri)
                    )
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                provider = ImageProvider(icon),
                contentDescription = label,
                modifier = GlanceModifier.size(36.dp)
            )
            Spacer(modifier = GlanceModifier.height(2.dp))
            Text(
                text = label,
                style = TextStyle(
                    color = colors.textSecondary,
                    fontSize = 10.sp
                )
            )
        }
    }

    /**
     * Compact action button sized to fill a 1x1 widget cell.
     * Width: controlled by caller via modifier (defaultWeight() from RowScope).
     * Height: derived from LocalSize breakpoint minus parent padding.
     * IMPORTANT: Do NOT use fillMaxSize() - it overrides the caller's width constraint.
     */
    @Composable
    private fun CompactActionButton(
        icon: Int,
        label: String,
        deepLinkUri: String,
        modifier: GlanceModifier = GlanceModifier,
        colors: WidgetColors = rememberWidgetColors()
    ) {
        val context = LocalContext.current
        // Fill entire cell height (no padding subtraction - outer cornerRadius clips edges)
        val cellHeight = LocalSize.current.height

        Box(
            modifier = modifier
                .height(cellHeight)
                .cornerRadius(10.dp)
                .background(colors.surface)
                .clickable(
                    actionStartActivity(
                        createDeepLinkIntent(context, deepLinkUri)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(icon),
                contentDescription = label,
                modifier = GlanceModifier.size(32.dp)
            )
        }
    }

    /**
     * Helper to create explicit DeepLink Intents.
     * Direct Intent launch avoids Glance's callback trampoline crash on some devices.
     */
    private fun createDeepLinkIntent(context: Context, uri: String): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            component = ComponentName(context, MainActivity::class.java)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }
}

suspend fun updateWidgetPendingCount(context: Context, glanceId: GlanceId, count: Int) {
    updateAppWidgetState(context, glanceId) { prefs ->
        prefs[ScannerWidget.PENDING_COUNT_KEY] = count
    }
    ScannerWidget().update(context, glanceId)
}
