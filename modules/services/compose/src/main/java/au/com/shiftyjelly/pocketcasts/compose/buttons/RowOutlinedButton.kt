package au.com.shiftyjelly.pocketcasts.compose.buttons

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ButtonColors
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.ButtonDefaults.outlinedBorder
import androidx.compose.material.Icon
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.shiftyjelly.pocketcasts.compose.AppTheme
import au.com.shiftyjelly.pocketcasts.ui.theme.Theme

@Composable
fun RowOutlinedButton(
    text: String,
    modifier: Modifier = Modifier,
    includePadding: Boolean = true,
    border: BorderStroke = outlinedBorder,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    iconImage: ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .then(if (includePadding) Modifier.padding(16.dp) else Modifier)
            .fillMaxWidth()
    ) {
        OutlinedButton(
            onClick = { onClick() },
            shape = RoundedCornerShape(12.dp),
            border = border,
            colors = colors,
            modifier = Modifier
                .fillMaxWidth()
        ) {
            iconImage?.let {
                Icon(imageVector = it, contentDescription = "")
            }

            Text(
                text = text,
                fontSize = 18.sp,
                modifier = Modifier.padding(6.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun RowOutlinedButtonLightPreview() {
    AppTheme(Theme.ThemeType.LIGHT) {
        RowOutlinedButton(
            text = "Share",
            iconImage = Icons.Default.Share,
            onClick = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun RowOutlinedButtonDarkPreview() {
    AppTheme(Theme.ThemeType.DARK) {
        RowOutlinedButton(
            text = "Share",
            iconImage = Icons.Default.Share,
            onClick = {}
        )
    }
}
