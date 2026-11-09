package com.e2eechat.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.e2eechat.app.ui.theme.*

/**
 * Polished Claymorphism Card with soft raised edge and gentle ambient shadow.
 * Replaces harsh outlines with a subtle, pillowy highlight edge.
 */
@Composable
fun ClayCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(26.dp),
    backgroundColor: Color = TeleXSurfaceRaised,
    elevation: Dp = 4.5.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                spotColor = Color(0x185C5547),
                ambientColor = Color(0x105C5547)
            )
            .border(
                width = 0.75.dp,
                color = Color.White.copy(alpha = 0.5f),
                shape = shape
            ),
        shape = shape,
        color = backgroundColor
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            content = content
        )
    }
}

/**
 * Reusable Claymorphism Raised Button with soft tactile 3D depth, upper highlight,
 * subtle lower shadow, interactive spring touch compression, and hover feedback.
 */
@Composable
fun ClayRaisedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    isSecondary: Boolean = false,
    shape: Shape = RoundedCornerShape(20.dp),
    contentColor: Color = if (isSecondary) TeleXTextPrimary else TeleXTextOnPrimary,
    backgroundColor: Color = if (isSecondary) Color(0xFFFAF7F0) else TeleXPrimary,
    content: (@Composable RowScope.() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()

    // Smooth spring compression on touch / click
    val buttonScale by animateFloatAsState(
        targetValue = when {
            !enabled -> 1f
            isPressed -> 0.965f
            isHovered -> 1.015f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "clayButtonScale"
    )

    // Subtle, balanced elevation (3dp for secondary Google button, 4.5dp for primary login)
    val defaultElevation = if (isSecondary) 3.dp else 4.5.dp
    val buttonElevation by animateDpAsState(
        targetValue = when {
            !enabled -> 0.dp
            isPressed -> 1.dp
            isHovered -> defaultElevation + 2.dp
            else -> defaultElevation
        },
        animationSpec = tween(durationMillis = 150),
        label = "clayButtonElevation"
    )

    val shadowColor = if (isSecondary) Color(0x185C5547) else Color(0x33334232)
    val highlightColor = if (isSecondary) Color.White.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.35f)

    Surface(
        onClick = onClick,
        enabled = enabled && !isLoading,
        interactionSource = interactionSource,
        shape = shape,
        color = if (enabled) backgroundColor else TeleXOutlineVariant.copy(alpha = 0.5f),
        contentColor = if (enabled) contentColor else TeleXTextMuted,
        modifier = modifier
            .height(52.dp)
            .scale(buttonScale)
            .shadow(
                elevation = buttonElevation,
                shape = shape,
                spotColor = shadowColor,
                ambientColor = shadowColor
            )
            .border(
                width = 0.75.dp,
                color = if (enabled) highlightColor else Color.Transparent,
                shape = shape
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = contentColor,
                    strokeWidth = 2.5.dp
                )
            } else if (content != null) {
                content()
            } else if (text != null) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.2.sp
                    ),
                    color = if (enabled) contentColor else TeleXTextMuted
                )
            }
        }
    }
}

/**
 * Polished Claymorphism Inset Text Field with softened inset shadows,
 * balanced subtle highlights, and smooth focus responsiveness.
 */
@Composable
fun ClayInsetTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = true,
    readOnly: Boolean = false,
    isError: Boolean = false,
    shape: Shape = RoundedCornerShape(16.dp)
) {
    var isFocused by remember { mutableStateOf(false) }

    val animatedBorderColor by animateColorAsState(
        targetValue = when {
            isError -> TeleXError
            isFocused -> TeleXPrimary
            else -> TeleXOutline.copy(alpha = 0.45f)
        },
        animationSpec = tween(durationMillis = 180),
        label = "insetBorderColor"
    )

    val animatedContainerColor by animateColorAsState(
        targetValue = if (isFocused) Color(0xFFF1ECE2) else TeleXSurfaceInset,
        animationSpec = tween(durationMillis = 180),
        label = "insetContainerColor"
    )

    // Pre-allocate gradient colors to avoid creating brush objects every draw frame
    val insetTopShadowColors = remember {
        listOf(Color(0x0C4E483D), Color.Transparent)
    }
    val insetBottomHighlightColors = remember {
        listOf(Color.Transparent, Color(0x24FFFFFF))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(animatedContainerColor)
            .border(
                width = if (isFocused) 1.5.dp else 0.75.dp,
                color = animatedBorderColor,
                shape = shape
            )
            .drawWithContent {
                drawContent()
                // Soft, balanced top inner shadow (gentle depth without heavy darkness)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = insetTopShadowColors,
                        startY = 0f,
                        endY = 6.dp.toPx()
                    )
                )
                // Subtle bottom inner highlight (soft sunken bevel reflection)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = insetBottomHighlightColors,
                        startY = size.height - 4.dp.toPx(),
                        endY = size.height
                    )
                )
            }
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused },
            label = label?.let { { Text(it) } },
            placeholder = placeholder?.let { { Text(it, color = TeleXTextMuted) } },
            trailingIcon = trailingIcon,
            leadingIcon = leadingIcon,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            readOnly = readOnly,
            isError = isError,
            shape = shape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent,
                errorBorderColor = Color.Transparent,
                focusedTextColor = TeleXTextPrimary,
                unfocusedTextColor = TeleXTextPrimary,
                disabledTextColor = TeleXTextMuted,
                focusedLabelColor = TeleXPrimary,
                unfocusedLabelColor = TeleXTextSecondary,
                cursorColor = TeleXPrimary
            )
        )
    }
}

/**
 * Subtle tactile clay pill badge.
 */
@Composable
fun ClayBadge(
    text: String,
    modifier: Modifier = Modifier,
    icon: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .shadow(
                elevation = 1.5.dp,
                shape = RoundedCornerShape(12.dp),
                spotColor = Color(0x144E483D),
                ambientColor = Color(0x144E483D)
            )
            .border(
                width = 0.75.dp,
                color = Color.White.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp)
            )
            .background(
                color = TeleXPrimaryContainer,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            icon()
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 0.4.sp
            ),
            color = TeleXPrimaryDeep
        )
    }
}
