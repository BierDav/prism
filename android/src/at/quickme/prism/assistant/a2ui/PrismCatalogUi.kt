// Prism's A2UI catalog for Google's Compose renderer (androidx.a2ui
// 1.0.0-alpha02): the basic catalog's 18 components, drawn in the look of
// the assistant's panel (alpha02 ships the basic catalog's contracts, not a
// Material implementation: A2uiBasicCatalogV1's interfaces, each implemented
// here), and the assistant's cards as components of their own, each drawn by
// its Gemini-style card (CardViews.kt). The catalog's ID and components are
// those core/PrismCatalog.kt checks show_ui against.

package at.quickme.prism.assistant.a2ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.a2ui.compose.runtime.A2uiComponentReference
import androidx.a2ui.compose.runtime.A2uiComponentScope
import androidx.a2ui.compose.runtime.A2uiComponentState
import androidx.a2ui.compose.runtime.A2uiComponentProperties
import androidx.a2ui.compose.runtime.A2uiProperty
import androidx.a2ui.compose.runtime.DynamicA2uiProperty
import androidx.a2ui.compose.runtime.ProvideActionInterceptor
import androidx.a2ui.compose.runtime.observeA2uiComponentState
import androidx.a2ui.compose.ui.A2uiCatalog
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1.AccessibilityAttributes
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1.CheckRule
import androidx.a2ui.compose.ui.catalog.toA2uiCatalog
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import at.quickme.prism.assistant.MarkdownText
import at.quickme.prism.assistant.core.A2ui
import at.quickme.prism.assistant.core.Card
import at.quickme.prism.assistant.core.PrismCatalog
import at.quickme.prism.assistant.core.UiAction
import at.quickme.prism.ui.PrismIcons
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1.Button as ButtonSpec
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1.ChoicePicker as ChoicePickerSpec
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1.Column as ColumnSpec
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1.Divider as DividerSpec
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1.Icon as IconSpec
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1.Image as ImageSpec
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1.List as ListSpec
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1.Row as RowSpec
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1.Text as TextSpec
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1.TextField as TextFieldSpec
import androidx.a2ui.model.catalog.A2uiFunctionCollection

/** Prism's catalog for the renderer: the basic components and the cards ([context] formats dates and numbers). */
fun prismCatalog(context: Context): A2uiCatalog {
    val basic = A2uiBasicCatalogV1(
        text = TextUi, image = ImageUi, icon = IconUi, video = VideoUi, audioPlayer = AudioUi,
        row = RowUi, column = ColumnUi, list = ListUi, card = CardUi, tabs = TabsUi, modal = ModalUi,
        divider = DividerUi, button = ButtonUi, textField = TextFieldUi, checkBox = CheckBoxUi,
        choicePicker = ChoicePickerUi, slider = SliderUi, dateTimeInput = DateTimeUi,
        functions = A2uiBasicCatalogV1.Functions(context),
    )
    return A2uiCatalog(
        catalogId = PrismCatalog.ID,
        components = basic.components + PrismCatalog.cards.map(::CardComponent),
        // Only functions that compute: openUrl would make the phone act from a surface the model wrote.
        functions = A2uiFunctionCollection(basic.toA2uiCatalog().functions.filter { it.definition.name in PrismCatalog.FUNCTIONS }),
    )
}

// --- The cards ---

/**
 * A card of the assistant as a component: its properties are the card's
 * fields (bound to the data model, or given as they are), read into the
 * card and drawn by [CardView]; its buttons dispatch A2UI events.
 */
private class CardComponent(spec: PrismCatalog.Spec) : A2uiComponent {
    private val serializer = spec.card!!
    override val name: String = spec.name
    override val description: String = spec.description
    private val fields: List<DynamicA2uiProperty<Any>> = spec.props
        .filter { it.kind == PrismCatalog.Kind.DYN_ANY && !it.name.startsWith("@") }
        .map { A2uiProperty.dynamicValue(it.name, it.required) }
    override val properties: List<A2uiProperty<*>> = fields + A2uiProperty.dynamicValue("accessibility") + A2uiBasicCatalogV1.WeightProperty

    @Composable
    override fun A2uiComponentScope.isReady(properties: A2uiComponentProperties): Boolean {
        var ready = true
        for (f in fields) if (properties.bind(f) == null && f.isRequired) ready = false
        return ready
    }

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val values = LinkedHashMap<String, Any>()
        for (f in fields) properties.bind(f)?.let { values[f.key] = it }
        val card: Card? = remember(values) {
            runCatching { PrismCatalog.lenient.decodeFromJsonElement(serializer, UiAction.toJson(values) as JsonObject) }.getOrNull()
        }
        if (card == null) return
        Box(modifier) {
            CardView(card) { event, context -> dispatchAction(mapOf("event" to mapOf("name" to event, "context" to context))) }
        }
    }
}

// --- Layout ---

/** A child by its reference, drawn when it resolved; [weighted] gives a Row's or Column's child its weight. */
@Composable
private fun A2uiComponentScope.Child(ref: A2uiComponentReference, modifier: Modifier = Modifier, weighted: (Modifier, Float) -> Modifier = { m, _ -> m }) {
    val state = observeA2uiComponentState(ref)
    if (state is A2uiComponentState.Success) {
        val weight = state.component.properties[A2uiBasicCatalogV1.WeightProperty]?.toFloat()?.takeIf { it > 0f }
        A2uiComponent(state.component, if (weight != null) weighted(modifier, weight) else modifier)
    }
}

@Composable
private fun A2uiComponentScope.Child(id: String, modifier: Modifier = Modifier) = Child(A2uiComponentReference(id), modifier)

private val Gap = 8.dp

private fun rowArrangement(j: RowSpec.Justify): Arrangement.Horizontal = when (j) {
    RowSpec.Justify.Center -> Arrangement.spacedBy(Gap, Alignment.CenterHorizontally)
    RowSpec.Justify.End -> Arrangement.spacedBy(Gap, Alignment.End)
    RowSpec.Justify.SpaceAround -> Arrangement.SpaceAround
    RowSpec.Justify.SpaceBetween -> Arrangement.SpaceBetween
    RowSpec.Justify.SpaceEvenly -> Arrangement.SpaceEvenly
    RowSpec.Justify.Start, RowSpec.Justify.Stretch -> Arrangement.spacedBy(Gap)
}

private fun columnArrangement(j: ColumnSpec.Justify): Arrangement.Vertical = when (j) {
    ColumnSpec.Justify.Center -> Arrangement.spacedBy(Gap, Alignment.CenterVertically)
    ColumnSpec.Justify.End -> Arrangement.spacedBy(Gap, Alignment.Bottom)
    ColumnSpec.Justify.SpaceAround -> Arrangement.SpaceAround
    ColumnSpec.Justify.SpaceBetween -> Arrangement.SpaceBetween
    ColumnSpec.Justify.SpaceEvenly -> Arrangement.SpaceEvenly
    ColumnSpec.Justify.Start, ColumnSpec.Justify.Stretch -> Arrangement.spacedBy(Gap)
}

private object RowUi : RowSpec {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        children: List<A2uiComponentReference>, justify: RowSpec.Justify, align: RowSpec.Align,
        accessibility: AccessibilityAttributes?, modifier: Modifier,
    ) {
        Row(
            modifier.fillMaxWidth().a11y(accessibility),
            horizontalArrangement = rowArrangement(justify),
            verticalAlignment = when (align) {
                RowSpec.Align.Start -> Alignment.Top
                RowSpec.Align.End -> Alignment.Bottom
                else -> Alignment.CenterVertically
            },
        ) {
            val row: RowScope = this
            children.forEach { ref ->
                Child(ref, if (justify == RowSpec.Justify.Stretch) with(row) { Modifier.weight(1f) } else Modifier) { m, w -> with(row) { m.weight(w) } }
            }
        }
    }
}

private object ColumnUi : ColumnSpec {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        children: List<A2uiComponentReference>, justify: ColumnSpec.Justify, align: ColumnSpec.Align,
        accessibility: AccessibilityAttributes?, modifier: Modifier,
    ) {
        Column(
            modifier.fillMaxWidth().a11y(accessibility),
            verticalArrangement = columnArrangement(justify),
            horizontalAlignment = when (align) {
                ColumnSpec.Align.Center -> Alignment.CenterHorizontally
                ColumnSpec.Align.End -> Alignment.End
                else -> Alignment.Start
            },
        ) {
            val column: ColumnScope = this
            children.forEach { ref ->
                Child(ref, if (align == ColumnSpec.Align.Stretch) Modifier.fillMaxWidth() else Modifier) { m, w -> with(column) { m.weight(w) } }
            }
        }
    }
}

/** A list in the chat's own scrolling list: a column (or a row that scrolls sideways), not a lazy list of its own. */
private object ListUi : ListSpec {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        children: List<A2uiComponentReference>, direction: ListSpec.Direction, align: ListSpec.Align,
        accessibility: AccessibilityAttributes?, modifier: Modifier,
    ) {
        if (direction == ListSpec.Direction.Horizontal) {
            Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).a11y(accessibility), horizontalArrangement = Arrangement.spacedBy(Gap)) {
                children.forEach { Child(it) }
            }
        } else {
            Column(
                modifier.fillMaxWidth().a11y(accessibility),
                verticalArrangement = Arrangement.spacedBy(Gap),
                horizontalAlignment = when (align) {
                    ListSpec.Align.Center -> Alignment.CenterHorizontally
                    ListSpec.Align.End -> Alignment.End
                    else -> Alignment.Start
                },
            ) { children.forEach { Child(it, if (align == ListSpec.Align.Stretch) Modifier.fillMaxWidth() else Modifier) } }
        }
    }
}

/** The cards' container: the action card's tonal surface, 20 dp corners. */
private object CardUi : A2uiBasicCatalogV1.Card {
    @Composable
    override fun A2uiComponentScope.TypedContent(childId: String, accessibility: AccessibilityAttributes?, modifier: Modifier) {
        Box(
            modifier.fillMaxWidth().a11y(accessibility)
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp))
                .padding(16.dp),
        ) { Child(childId) }
    }
}

private object TabsUi : A2uiBasicCatalogV1.Tabs {
    @Composable
    override fun A2uiComponentScope.TypedContent(tabs: List<A2uiBasicCatalogV1.Tabs.Tab>, accessibility: AccessibilityAttributes?, modifier: Modifier) {
        var selected by remember { mutableIntStateOf(0) }
        Column(modifier.fillMaxWidth().a11y(accessibility), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryTabRow(selectedTabIndex = selected.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)), containerColor = androidx.compose.ui.graphics.Color.Transparent) {
                tabs.forEachIndexed { i, t -> Tab(selected = i == selected, onClick = { selected = i }, text = { Text(t.title) }) }
            }
            tabs.getOrNull(selected)?.let { Child(it.childId) }
        }
    }
}

/** The trigger, whose action opens the content in a bottom sheet instead of reaching the model. */
private object ModalUi : A2uiBasicCatalogV1.Modal {
    @Composable
    override fun A2uiComponentScope.TypedContent(triggerId: String, contentId: String, accessibility: AccessibilityAttributes?, modifier: Modifier) {
        var open by remember { mutableStateOf(false) }
        Box(modifier.a11y(accessibility)) {
            ProvideActionInterceptor(onIntercept = { open = true; true }) { Child(triggerId) }
        }
        if (open) ModalBottomSheet(onDismissRequest = { open = false }) {
            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) { Child(contentId) }
        }
    }
}

private object DividerUi : DividerSpec {
    @Composable
    override fun A2uiComponentScope.TypedContent(axis: DividerSpec.Axis, accessibility: AccessibilityAttributes?, modifier: Modifier) {
        val color = MaterialTheme.colorScheme.outlineVariant
        if (axis == DividerSpec.Axis.Vertical) VerticalDivider(modifier.height(24.dp), color = color)
        else HorizontalDivider(modifier, color = color)
    }
}

// --- Content ---

private object TextUi : TextSpec {
    @Composable
    override fun A2uiComponentScope.TypedContent(text: String, variant: TextSpec.Variant, accessibility: AccessibilityAttributes?, modifier: Modifier) {
        val t = MaterialTheme.typography
        val colors = MaterialTheme.colorScheme
        // A button's label: in the button's own colour and type.
        if (LocalInButton.current) {
            Text(text, modifier.a11y(accessibility), style = t.labelLarge, color = LocalContentColor.current, maxLines = 1)
            return
        }
        when (variant) {
            TextSpec.Variant.Body -> MarkdownText(text, modifier.a11y(accessibility))
            TextSpec.Variant.Caption -> Text(text, modifier.a11y(accessibility), style = t.bodySmall, color = colors.onSurfaceVariant)
            else -> Text(
                text, modifier.a11y(accessibility), color = colors.onSurface,
                style = when (variant) {
                    TextSpec.Variant.H1 -> t.headlineMedium
                    TextSpec.Variant.H2 -> t.headlineSmall
                    TextSpec.Variant.H3 -> t.titleLarge
                    TextSpec.Variant.H4 -> t.titleMedium
                    else -> t.titleSmall
                },
            )
        }
    }
}

private object ImageUi : ImageSpec {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        url: String, description: String?, fit: ImageSpec.Fit, variant: ImageSpec.Variant,
        accessibility: AccessibilityAttributes?, modifier: Modifier,
    ) {
        val image = rememberUrlImage(url)
        val shape = if (variant == ImageSpec.Variant.Avatar) CircleShape else RoundedCornerShape(16.dp)
        val size = when (variant) {
            ImageSpec.Variant.Icon -> Modifier.size(24.dp)
            ImageSpec.Variant.Avatar -> Modifier.size(40.dp)
            ImageSpec.Variant.SmallFeature -> Modifier.size(72.dp)
            ImageSpec.Variant.LargeFeature, ImageSpec.Variant.Header -> Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            ImageSpec.Variant.MediumFeature -> Modifier.fillMaxWidth().aspectRatio(4f / 3f)
        }
        val m = modifier.then(size).clip(shape).a11y(accessibility)
        if (image == null) Box(m.background(MaterialTheme.colorScheme.surfaceContainerHigh))
        else Image(
            image, description, m,
            contentScale = when (fit) {
                ImageSpec.Fit.Contain, ImageSpec.Fit.ScaleDown -> ContentScale.Fit
                ImageSpec.Fit.Cover -> ContentScale.Crop
                ImageSpec.Fit.None -> ContentScale.None
                ImageSpec.Fit.Fill -> ContentScale.FillBounds
            },
        )
    }
}

/** An image fetched over HTTPS (at most 8 MB), or null while it loads or when it can't. */
@Composable
private fun rememberUrlImage(url: String): ImageBitmap? {
    val image by produceState<ImageBitmap?>(null, url) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (!url.startsWith("https://")) return@runCatching null
                val c = java.net.URL(url).openConnection().apply { connectTimeout = 10_000; readTimeout = 15_000 }
                val bytes = c.getInputStream().use { it.readNBytes(8 * 1024 * 1024) }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    return image
}

private object IconUi : IconSpec {
    @Composable
    override fun A2uiComponentScope.TypedContent(source: IconSpec.Source, accessibility: AccessibilityAttributes?, modifier: Modifier) {
        val vector = when (source) {
            is IconSpec.BuiltIn -> builtIn(source.value)
            is IconSpec.SvgPath -> remember(source.svgPath) { svgIcon(source.svgPath) }
            else -> null
        } ?: return
        Icon(vector, accessibility?.label, modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun svgIcon(path: String): ImageVector? = runCatching {
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(addPathNodes(path), fill = SolidColor(androidx.compose.ui.graphics.Color.Black)).build()
}.getOrNull()

private fun builtIn(name: String): ImageVector = when (name) {
    "accountCircle" -> Icons.Rounded.AccountCircle
    "add" -> Icons.Rounded.Add
    "arrowBack", "rewind", "skipPrevious" -> Icons.AutoMirrored.Rounded.ArrowBack
    "arrowForward", "fastForward", "skipNext" -> Icons.AutoMirrored.Rounded.ArrowForward
    "calendarToday", "event" -> Icons.Rounded.DateRange
    "call" -> Icons.Rounded.Call
    "phone" -> Icons.Rounded.Phone
    "check" -> Icons.Rounded.Check
    "close" -> Icons.Rounded.Close
    "delete" -> Icons.Rounded.Delete
    "edit" -> Icons.Rounded.Edit
    "error", "warning" -> Icons.Rounded.Warning
    "favorite" -> Icons.Rounded.Favorite
    "favoriteOff" -> Icons.Rounded.FavoriteBorder
    "home" -> Icons.Rounded.Home
    "locationOn" -> Icons.Rounded.LocationOn
    "lock", "lockOpen" -> Icons.Rounded.Lock
    "mail" -> Icons.Rounded.Email
    "menu" -> Icons.Rounded.Menu
    "moreHoriz", "moreVert" -> Icons.Rounded.MoreVert
    "notifications", "notificationsOff" -> Icons.Rounded.Notifications
    "person" -> Icons.Rounded.Person
    "play" -> Icons.Rounded.PlayArrow
    "pause", "stop" -> PrismIcons.Stop
    "refresh" -> Icons.Rounded.Refresh
    "search" -> Icons.Rounded.Search
    "send" -> Icons.AutoMirrored.Rounded.Send
    "settings" -> Icons.Rounded.Settings
    "share" -> Icons.Rounded.Share
    "shoppingCart" -> Icons.Rounded.ShoppingCart
    "star", "starHalf", "starOff" -> Icons.Rounded.Star
    else -> Icons.Rounded.Info
}

/**
 * Media the renderer has no player for: tapping it asks the model to open it
 * (an event, so a turn; the model then calls open_url), never opens it here.
 */
@Composable
private fun A2uiComponentScope.MediaLink(url: String, label: String, accessibility: AccessibilityAttributes?, modifier: Modifier) {
    val enabled = LocalCardsAct.current && LocalSurfaceStatus.current.busy == null
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled) {
                dispatchAction(mapOf("event" to mapOf("name" to OPEN_MEDIA, "context" to mapOf("url" to url, A2ui.USER_MESSAGE to "$label: $url"))))
            }
            .padding(12.dp).a11y(accessibility),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp), maxLines = 2)
    }
}

private object VideoUi : A2uiBasicCatalogV1.Video {
    @Composable
    override fun A2uiComponentScope.TypedContent(url: String, accessibility: AccessibilityAttributes?, modifier: Modifier) =
        MediaLink(url, "Play the video", accessibility, modifier)
}

private object AudioUi : A2uiBasicCatalogV1.AudioPlayer {
    @Composable
    override fun A2uiComponentScope.TypedContent(url: String, description: String?, accessibility: AccessibilityAttributes?, modifier: Modifier) =
        MediaLink(url, description ?: "Play", accessibility, modifier)
}

// --- Input ---

/** Whether what is drawn is a button's label. */
private val LocalInButton = compositionLocalOf { false }

/** The first failed check's message, if one failed. */
private fun List<CheckRule>.failed(): String? = firstOrNull { !it.condition }?.message

private object ButtonUi : ButtonSpec {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        childId: String, variant: ButtonSpec.Variant, action: Map<String, Any?>,
        accessibility: AccessibilityAttributes?, checks: List<CheckRule>, modifier: Modifier,
    ) {
        val enabled = LocalCardsAct.current && checks.failed() == null && LocalSurfaceStatus.current.busy == null
        val onClick = { dispatchAction(action) }
        val m = modifier.a11y(accessibility)
        val label: @Composable () -> Unit = { CompositionLocalProvider(LocalInButton provides true) { Child(childId) } }
        when (variant) {
            ButtonSpec.Variant.Primary -> Button(onClick, m, enabled = enabled) { label() }
            ButtonSpec.Variant.Secondary -> FilledTonalButton(onClick, m, enabled = enabled) { label() }
            ButtonSpec.Variant.Borderless -> TextButton(onClick, m, enabled = enabled) { label() }
        }
    }
}

private object TextFieldUi : TextFieldSpec {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        label: String, value: String?, variant: TextFieldSpec.Variant, validationRegexp: String?,
        onValueChange: (String) -> Unit, enabled: Boolean, accessibility: AccessibilityAttributes?,
        checks: List<CheckRule>, modifier: Modifier,
    ) {
        val text = value.orEmpty()
        val invalid = validationRegexp?.let { r -> text.isNotEmpty() && runCatching { !Regex(r).matches(text) }.getOrDefault(false) } == true
        val problem = checks.failed() ?: if (invalid) "Not in the expected form" else null
        OutlinedTextField(
            value = text,
            onValueChange = onValueChange,
            label = { Text(label) },
            enabled = enabled && LocalCardsAct.current,
            isError = problem != null && text.isNotEmpty(),
            supportingText = problem?.takeIf { text.isNotEmpty() }?.let { { Text(it) } },
            singleLine = variant != TextFieldSpec.Variant.LongText,
            minLines = if (variant == TextFieldSpec.Variant.LongText) 3 else 1,
            keyboardOptions = when (variant) {
                TextFieldSpec.Variant.Number -> KeyboardOptions(keyboardType = KeyboardType.Number)
                TextFieldSpec.Variant.Obscured -> KeyboardOptions(keyboardType = KeyboardType.Password)
                else -> KeyboardOptions.Default
            },
            // Compose 1.13 deprecates VisualTransformation with the value-based fields.
            visualTransformation = @Suppress("DEPRECATION") if (variant == TextFieldSpec.Variant.Obscured) PasswordVisualTransformation() else VisualTransformation.None,
            shape = RoundedCornerShape(16.dp),
            modifier = modifier.fillMaxWidth().a11y(accessibility),
        )
    }
}

private object CheckBoxUi : A2uiBasicCatalogV1.CheckBox {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        label: String, value: Boolean, onValueChange: (Boolean) -> Unit, enabled: Boolean,
        accessibility: AccessibilityAttributes?, checks: List<CheckRule>, modifier: Modifier,
    ) {
        val on = enabled && LocalCardsAct.current
        Column(modifier.a11y(accessibility)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = on) { onValueChange(!value) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = value, onCheckedChange = if (on) onValueChange else null)
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 4.dp, end = 8.dp))
            }
            checks.failed()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 48.dp)) }
        }
    }
}

/** Options as Gemini lists choices: rows with a radio or check, or chips; filterable with a search field. */
private object ChoicePickerUi : ChoicePickerSpec {
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun A2uiComponentScope.TypedContent(
        label: String?, options: List<ChoicePickerSpec.Option>, value: List<String>, variant: ChoicePickerSpec.Variant,
        displayStyle: ChoicePickerSpec.DisplayStyle, filterable: Boolean, onValueChange: (List<String>) -> Unit,
        enabled: Boolean, accessibility: AccessibilityAttributes?, checks: List<CheckRule>, modifier: Modifier,
    ) {
        val single = variant == ChoicePickerSpec.Variant.MutuallyExclusive
        val on = enabled && LocalCardsAct.current
        var filter by remember { mutableStateOf("") }
        fun pick(o: ChoicePickerSpec.Option) {
            if (!on) return
            onValueChange(if (single) listOf(o.value) else if (o.value in value) value - o.value else value + o.value)
        }
        val shown = if (filter.isBlank()) options else options.filter { it.label.contains(filter.trim(), ignoreCase = true) }
        Column(modifier.fillMaxWidth().a11y(accessibility), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            label?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface) }
            if (filterable) OutlinedTextField(
                value = filter, onValueChange = { filter = it }, singleLine = true, placeholder = { Text("Search") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            )
            if (displayStyle == ChoicePickerSpec.DisplayStyle.Chips) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    shown.forEach { o -> FilterChip(selected = o.value in value, onClick = { pick(o) }, label = { Text(o.label) }, enabled = on) }
                }
            } else shown.forEach { o ->
                val picked = o.value in value
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (picked) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(enabled = on) { pick(o) }.padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (single) RadioButton(selected = picked, onClick = { pick(o) }, enabled = on)
                    else Checkbox(checked = picked, onCheckedChange = { pick(o) }, enabled = on)
                    Text(o.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 4.dp))
                }
            }
            checks.failed()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private object SliderUi : A2uiBasicCatalogV1.Slider {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        label: String?, min: Float, max: Float, value: Float, onValueChange: (Float) -> Unit, enabled: Boolean,
        accessibility: AccessibilityAttributes?, checks: List<CheckRule>, modifier: Modifier,
    ) {
        val drawable = min.isFinite() && max.isFinite() && min < max
        // A bound value outside the range is shown at the nearest end (validation refuses a written one).
        val shown = if (!drawable) value else if (value.isFinite()) value.coerceIn(min, max) else min
        Column(modifier.fillMaxWidth().a11y(accessibility)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                label?.let { Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)) }
                Text(if (shown % 1f == 0f) shown.toInt().toString() else "%.1f".format(shown), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            // Validation rejects a range that can't be drawn (PrismCatalog); one stored from before is shown, not drawn.
            if (!drawable) Text("This slider's range ($min to $max) can't be shown.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            // material3 1.5 deprecates the value-based Slider; the renderer hands a value and a callback.
            else {
                @Suppress("DEPRECATION")
                Slider(value = shown, onValueChange = onValueChange, valueRange = min..max, enabled = enabled && LocalCardsAct.current)
            }
            checks.failed()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** A date and/or time: what is set, and Material's date picker and time input on a tap. */
private object DateTimeUi : A2uiBasicCatalogV1.DateTimeInput {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        value: Long?, onValueChange: ((Long?) -> Unit)?, enableDate: Boolean, enableTime: Boolean,
        min: Long?, max: Long?, label: String?, accessibility: AccessibilityAttributes?,
        checks: List<CheckRule>, modifier: Modifier,
    ) {
        var step by remember { mutableIntStateOf(0) } // 0 closed, 1 date, 2 time
        var date by remember { mutableStateOf<Long?>(null) }
        val withDate = enableDate || !enableTime
        val utc = TimeZone.getTimeZone("UTC")
        val shown = value?.let { v ->
            val f = when {
                withDate && enableTime -> DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                withDate -> DateFormat.getDateInstance(DateFormat.MEDIUM)
                else -> DateFormat.getTimeInstance(DateFormat.SHORT)
            }
            f.timeZone = utc
            f.format(Date(v))
        }
        val can = onValueChange != null && LocalCardsAct.current
        Column(modifier.fillMaxWidth().a11y(accessibility)) {
            label?.let { Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp)) }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(enabled = can) { step = if (withDate) 1 else 2 }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.DateRange, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text(shown ?: "Choose", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
            }
            checks.failed()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        if (step == 1) {
            val state = rememberDatePickerState(initialSelectedDateMillis = value?.let { it - it % 86_400_000L })
            DatePickerDialog(
                onDismissRequest = { step = 0 },
                confirmButton = {
                    TextButton(onClick = {
                        val day = state.selectedDateMillis
                        if (enableTime && day != null) { date = day; step = 2 }
                        else { day?.let { d -> onValueChange?.invoke(d.coerceIn(min ?: Long.MIN_VALUE, max ?: Long.MAX_VALUE)) }; step = 0 }
                    }) { Text(if (enableTime) "Next" else "OK") }
                },
                dismissButton = { TextButton(onClick = { step = 0 }) { Text("Cancel") } },
            ) { DatePicker(state) }
        }
        if (step == 2) {
            val start = value ?: 0L
            val state = rememberTimePickerState(initialHour = ((start / 3_600_000L) % 24).toInt(), initialMinute = ((start / 60_000L) % 60).toInt())
            AlertDialog(
                onDismissRequest = { step = 0 },
                confirmButton = {
                    TextButton(onClick = {
                        val day = if (withDate) (date ?: value?.let { it - it % 86_400_000L } ?: 0L) else 0L
                        onValueChange?.invoke(day + state.hour * 3_600_000L + state.minute * 60_000L)
                        step = 0
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { step = 0 }) { Text("Cancel") } },
                text = { TimeInput(state) },
            )
        }
    }
}

/** A component's accessibility attributes as its semantics. */
private fun Modifier.a11y(a: AccessibilityAttributes?): Modifier {
    val label = listOfNotNull(a?.label, a?.description).joinToString(". ").takeIf { it.isNotEmpty() } ?: return this
    return semantics { contentDescription = label }
}

/** What a tap on a video or a sound sends: a turn asking the model to open [url]. */
const val OPEN_MEDIA = "open_media"
