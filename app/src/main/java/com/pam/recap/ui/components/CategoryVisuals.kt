package com.pam.recap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryColorRole
import com.pam.recap.ui.theme.Amber
import com.pam.recap.ui.theme.AmberDeep
import com.pam.recap.ui.theme.ExpenseRose
import com.pam.recap.ui.theme.IncomeMint
import com.pam.recap.ui.theme.Magenta
import com.pam.recap.ui.theme.OnInkFaint
import com.pam.recap.ui.theme.Pink
import com.pam.recap.ui.theme.Violet
import com.pam.recap.ui.theme.VioletSoft
import com.pam.recap.ui.theme.WarningAmber

/**
 * Maps the domain's [CategoryColorRole] onto theme tokens. Feature code never inlines a colour:
 * the role comes from `domain/model/Taxonomy.kt`, the value from `ui/theme/Color.kt`.
 */
@Composable
fun categoryColor(role: CategoryColorRole): Color = when (role) {
    CategoryColorRole.INCOME_MINT -> IncomeMint
    CategoryColorRole.BRAND_VIOLET -> Violet
    CategoryColorRole.BRAND_MAGENTA -> Magenta
    CategoryColorRole.BRAND_PINK -> Pink
    CategoryColorRole.VIOLET_SOFT -> VioletSoft
    CategoryColorRole.AMBER -> Amber
    CategoryColorRole.AMBER_DEEP -> AmberDeep
    CategoryColorRole.ROSE -> ExpenseRose
    CategoryColorRole.NEUTRAL -> OnInkFaint
    CategoryColorRole.WARNING -> WarningAmber
}

/** Line icon per category, reused by rows, chips and the legend. */
fun categoryIcon(category: Category): ImageVector = when (category) {
    Category.INCOME -> Icons.Rounded.AccountBalanceWallet
    Category.HOUSING -> Icons.Rounded.Home
    Category.FOOD -> Icons.Rounded.Restaurant
    Category.TRANSPORT -> Icons.Rounded.DirectionsBus
    Category.UTILITIES -> Icons.Rounded.Bolt
    Category.SHOPPING -> Icons.Rounded.ShoppingBag
    Category.HEALTH -> Icons.Rounded.MedicalServices
    Category.EDUCATION -> Icons.Rounded.School
    Category.OTHER_EXPENSE -> Icons.Rounded.Category
    Category.TRANSFER -> Icons.Rounded.SwapHoriz
    Category.UNCATEGORIZED -> Icons.Rounded.HelpOutline
}

/**
 * Circular category glyph used by transaction rows and the category breakdown. Decorative: the
 * visible label next to it carries the meaning, so the icon has no content description.
 */
@Composable
fun CategoryGlyph(
    category: Category,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val color = categoryColor(category.colorRole)
    Box(
        modifier = modifier
            .size(size)
            .background(color.copy(alpha = 0.14f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = categoryIcon(category),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(size * 0.45f),
        )
    }
}
