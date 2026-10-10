/*
 * Copyright (C) 2021-2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.setup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.florisboard.lib.compose.florisVerticalScroll

private val NumberSize = 40.dp
private val NumberEndPadding = 16.dp

data class SetupStep(
    val id: Int,
    val title: String,
    val content: @Composable ColumnScope.() -> Unit,
)

/** Automatic progress may advance while the user revisits any step already reached. */
class SetupStepState private constructor(automatic: Int, manual: Int = -1) {
    var automatic by mutableIntStateOf(automatic)
        private set
    var manual by mutableIntStateOf(manual)
        private set
    val current get() = if (manual >= 0 && automatic >= manual) manual else automatic

    fun updateAutomatic(step: Int) { automatic = step }
    fun select(step: Int) { manual = if (step == automatic) -1 else step }

    companion object {
        fun new(initial: Int) = SetupStepState(initial)

        val Saver = Saver<SetupStepState, ArrayList<Int>>(
            save = { arrayListOf(it.automatic, it.manual) },
            restore = { SetupStepState(it[0], it[1]) },
        )
    }
}

@Composable
fun ColumnScope.StepText(text: String, modifier: Modifier = Modifier, fontStyle: FontStyle = FontStyle.Normal) {
    Text(modifier = modifier, text = text, textAlign = TextAlign.Justify, fontStyle = fontStyle)
}

@Composable
fun ColumnScope.StepButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        modifier = modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp),
        onClick = onClick,
    ) { Text(text = label) }
}

@Composable
fun SetupStepLayout(
    stepState: SetupStepState,
    steps: List<SetupStep>,
    modifier: Modifier = Modifier,
    header: @Composable ColumnScope.() -> Unit = {},
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    Column(modifier = modifier.fillMaxSize()) {
        header()
        steps.forEachIndexed { index, step ->
            key(step.id) { Step(step, index + 1, stepState, primaryColor) }
        }
        footer()
    }
}

@Composable
private fun ColumnScope.Step(step: SetupStep, index: Int, state: SetupStepState, primaryColor: Color) {
    val visible = step.id == state.current
    StepHeader(
        modifier = if (step.id <= state.automatic) {
            Modifier.clickable(enabled = !visible) { state.select(step.id) }
        } else {
            Modifier.alpha(0.38f)
        },
        backgroundColor = if (visible) primaryColor else primaryColor.copy(alpha = 0.38f),
        index = index,
        title = step.title,
    )
    val animSpec = spring<Float>(stiffness = Spring.StiffnessMedium)
    val weight by animateFloatAsState(
        targetValue = if (visible) 1f else 0.00001f,
        animationSpec = animSpec,
    )
    AnimatedVisibility(
        modifier = Modifier.fillMaxWidth().weight(weight),
        visible = visible,
        enter = fadeIn(animationSpec = animSpec),
        exit = fadeOut(animationSpec = animSpec),
    ) {
        val onBackground = MaterialTheme.colorScheme.onSurface
        Box(
            modifier = Modifier.padding(start = 56.dp).drawBehind {
                val strokeWidth = 2.dp
                val x = -(NumberEndPadding + (NumberSize / 2 - strokeWidth / 2))
                drawLine(
                    color = onBackground,
                    start = Offset(x.toPx(), 0f),
                    end = Offset(x.toPx(), size.height),
                    strokeWidth = strokeWidth.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 10.dp.toPx())),
                    alpha = 0.12f,
                )
            },
        ) {
            Column(Modifier.fillMaxSize().florisVerticalScroll().padding(end = 8.dp)) {
                step.content(this)
            }
        }
    }
}

@Composable
private fun StepHeader(modifier: Modifier, backgroundColor: Color, index: Int, title: String) {
    val contentColor = contentColorFor(backgroundColor)
    Row(modifier = modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.padding(end = NumberEndPadding).size(NumberSize)
                .clip(CircleShape).background(backgroundColor),
        ) {
            Text(modifier = Modifier.align(Alignment.Center), text = index.toString(), color = contentColor)
        }
        Box(
            modifier = Modifier.height(32.dp).weight(1f).clip(CircleShape).background(backgroundColor),
        ) {
            Text(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
                text = title,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                color = contentColor,
            )
        }
    }
}
