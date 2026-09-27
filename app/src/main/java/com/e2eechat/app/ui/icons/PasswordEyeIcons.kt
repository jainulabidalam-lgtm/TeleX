package com.e2eechat.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

val EyeOpenIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "EyeOpen",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(
                "M 2.4787 10.6666 C 4.8987 8.1986 8.2707 6.6666 12 6.6666 C 15.7293 6.6666 19.1013 8.1973 21.5213 10.6666"
            ).toNodes(),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        )
        addPath(
            pathData = PathParser().parsePathString(
                "M 8.3333 14 A 3.6667 3.6667 0 1 0 15.6667 14 A 3.6667 3.6667 0 1 0 8.3333 14"
            ).toNodes(),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        )
        addPath(
            pathData = PathParser().parsePathString("M 5.3613 8.4373 L 3.6667 5.6667").toNodes(),
            fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
        )
        addPath(
            pathData = PathParser().parsePathString("M 9.7333 6.8586 L 9.0973 3.692").toNodes(),
            fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
        )
        addPath(
            pathData = PathParser().parsePathString("M 18.6386 8.4373 L 20.3333 5.6667").toNodes(),
            fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
        )
        addPath(
            pathData = PathParser().parsePathString("M 14.2666 6.8586 L 14.9026 3.692").toNodes(),
            fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
        )
    }.build()
}

val EyeOffIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "EyeOff",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(
                "M22.2954 6.31083C22.6761 6.474 22.8524 6.91491 22.6893 7.29563L21.9999 7.00019C22.6893 7.29563 22.6894 7.29546 22.6893 7.29563L22.6886 7.29731L22.6875 7.2998L22.6843 7.30716L22.6736 7.33123C22.6646 7.35137 22.6518 7.37958 22.6352 7.41527C22.6019 7.48662 22.5533 7.58794 22.4888 7.71435C22.3599 7.967 22.1675 8.32087 21.9084 8.73666C21.4828 9.4197 20.8724 10.2778 20.0619 11.1304L21.0303 12.0987C21.3231 12.3916 21.3231 12.8665 21.0303 13.1594C20.7374 13.4523 20.2625 13.4523 19.9696 13.1594L18.969 12.1588C18.3093 12.7115 17.5528 13.2302 16.695 13.6564L17.6286 15.0912C17.8545 15.4383 17.7562 15.9029 17.409 16.1288C17.0618 16.3547 16.5972 16.2564 16.3713 15.9092L15.2821 14.2353C14.5028 14.4898 13.659 14.6628 12.7499 14.7248V16.5002C12.7499 16.9144 12.4141 17.2502 11.9999 17.2502C11.5857 17.2502 11.2499 16.9144 11.2499 16.5002V14.7248C10.3689 14.6647 9.54909 14.5004 8.78982 14.2586L7.71575 15.9093C7.48984 16.2565 7.02526 16.3548 6.67807 16.1289C6.33089 15.903 6.23257 15.4384 6.45847 15.0912L7.37089 13.689C6.5065 13.2668 5.74381 12.7504 5.07842 12.1984L4.11744 13.1594C3.82455 13.4523 3.34968 13.4523 3.05678 13.1594C2.76389 12.8665 2.76389 12.3917 3.05678 12.0988L3.98055 11.175C3.15599 10.3153 2.53525 9.44675 2.10277 8.75486C1.83984 8.33423 1.6446 7.97584 1.51388 7.71988C1.44848 7.59182 1.3991 7.48914 1.36537 7.41683C1.3485 7.38067 1.33553 7.35207 1.32641 7.33167L1.31562 7.30729L1.31238 7.29984L1.31129 7.29733L1.31088 7.29638C1.31081 7.2962 1.31056 7.29563 1.99992 7.00019L1.31088 7.29638C1.14772 6.91565 1.32376 6.474 1.70448 6.31083C2.08489 6.1478 2.52539 6.32374 2.68888 6.70381C2.68882 6.70368 2.68894 6.70394 2.68888 6.70381L2.68983 6.706L2.69591 6.71972C2.7018 6.73291 2.7114 6.7541 2.72472 6.78267C2.75139 6.83983 2.79296 6.92644 2.84976 7.03767C2.96345 7.26029 3.13762 7.58046 3.37472 7.95979C3.85033 8.72067 4.57157 9.70728 5.55561 10.6218C6.42151 11.4265 7.48259 12.1678 8.75165 12.656C9.70614 13.0232 10.7854 13.2502 11.9999 13.2502C13.2416 13.2502 14.342 13.013 15.3124 12.631C16.5738 12.1345 17.6277 11.3884 18.4866 10.5822C19.4562 9.67216 20.1668 8.69535 20.6354 7.9434C20.869 7.5685 21.0405 7.25246 21.1525 7.03286C21.2085 6.92315 21.2494 6.83776 21.2757 6.78144C21.2888 6.75328 21.2983 6.73242 21.3041 6.71943L21.31 6.70595L21.3106 6.70475C21.3105 6.70485 21.3106 6.70466 21.3106 6.70475M22.2954 6.31083C21.9147 6.14771 21.4738 6.32423 21.3106 6.70475L22.2954 6.31083ZM2.68888 6.70381C2.68882 6.70368 2.68894 6.70394 2.68888 6.70381V6.70381Z"
            ).toNodes(),
            fill = SolidColor(Color.Black),
            fillAlpha = 1f,
            pathFillType = PathFillType.EvenOdd
        )
    }.build()
}

val CalendarIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Calendar",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        addPath(
            pathData = PathParser().parsePathString("M17 14C17.5523 14 18 13.5523 18 13C18 12.4477 17.5523 12 17 12C16.4477 12 16 12.4477 16 13C16 13.5523 16.4477 14 17 14Z").toNodes(),
            fill = SolidColor(Color.Black)
        )
        addPath(
            pathData = PathParser().parsePathString("M17 18C17.5523 18 18 17.5523 18 17C18 16.4477 17.5523 16 17 16C16.4477 16 16 16.4477 16 17C16 17.5523 16.4477 18 17 18Z").toNodes(),
            fill = SolidColor(Color.Black)
        )
        addPath(
            pathData = PathParser().parsePathString("M13 13C13 13.5523 12.5523 14 12 14C11.4477 14 11 13.5523 11 13C11 12.4477 11.4477 12 12 12C12.5523 12 13 12.4477 13 13Z").toNodes(),
            fill = SolidColor(Color.Black)
        )
        addPath(
            pathData = PathParser().parsePathString("M13 17C13 17.5523 12.5523 18 12 18C11.4477 18 11 17.5523 11 17C11 16.4477 11.4477 16 12 16C12.5523 16 13 16.4477 13 17Z").toNodes(),
            fill = SolidColor(Color.Black)
        )
        addPath(
            pathData = PathParser().parsePathString("M7 14C7.55229 14 8 13.5523 8 13C8 12.4477 7.55229 12 7 12C6.44772 12 6 12.4477 6 13C6 13.5523 6.44772 14 7 14Z").toNodes(),
            fill = SolidColor(Color.Black)
        )
        addPath(
            pathData = PathParser().parsePathString("M7 18C7.55229 18 8 17.5523 8 17C8 16.4477 7.55229 16 7 16C6.44772 16 6 16.4477 6 17C6 17.5523 6.44772 18 7 18Z").toNodes(),
            fill = SolidColor(Color.Black)
        )
        addPath(
            pathData = PathParser().parsePathString("M7 1.75C7.41421 1.75 7.75 2.08579 7.75 2.5V3.26272C8.412 3.24999 9.14133 3.24999 9.94346 3.25H14.0564C14.8586 3.24999 15.588 3.24999 16.25 3.26272V2.5C16.25 2.08579 16.5858 1.75 17 1.75C17.4142 1.75 17.75 2.08579 17.75 2.5V3.32709C18.0099 3.34691 18.2561 3.37182 18.489 3.40313C19.6614 3.56076 20.6104 3.89288 21.3588 4.64124C22.1071 5.38961 22.4392 6.33855 22.5969 7.51098C22.75 8.65018 22.75 10.1058 22.75 11.9435V14.0564C22.75 15.8941 22.75 17.3498 22.5969 18.489C22.4392 19.6614 22.1071 20.6104 21.3588 21.3588C20.6104 22.1071 19.6614 22.4392 18.489 22.5969C17.3498 22.75 15.8942 22.75 14.0565 22.75H9.94359C8.10585 22.75 6.65018 22.75 5.51098 22.5969C4.33856 22.4392 3.38961 22.1071 2.64124 21.3588C1.89288 20.6104 1.56076 19.6614 1.40314 18.489C1.24997 17.3498 1.24998 15.8942 1.25 14.0564V11.9436C1.24998 10.1058 1.24997 8.65019 1.40314 7.51098C1.56076 6.33855 1.89288 5.38961 2.64124 4.64124C3.38961 3.89288 4.33856 3.56076 5.51098 3.40313C5.7439 3.37182 5.99006 3.34691 6.25 3.32709V2.5C6.25 2.08579 6.58579 1.75 7 1.75ZM5.71085 4.88976C4.70476 5.02502 4.12511 5.27869 3.7019 5.7019C3.27869 6.12511 3.02502 6.70476 2.88976 7.71085C2.86685 7.88123 2.8477 8.06061 2.83168 8.25H21.1683C21.1523 8.06061 21.1331 7.88124 21.1102 7.71085C20.975 6.70476 20.7213 6.12511 20.2981 5.7019C19.8749 5.27869 19.2952 5.02502 18.2892 4.88976C17.2615 4.75159 15.9068 4.75 14 4.75H10C8.09318 4.75 6.73851 4.75159 5.71085 4.88976ZM2.75 12C2.75 11.146 2.75032 10.4027 2.76309 9.75H21.2369C21.2497 10.4027 21.25 11.146 21.25 12V14C21.25 15.9068 21.2484 17.2615 21.1102 18.2892C20.975 19.2952 20.7213 19.8749 20.2981 20.2981C19.8749 20.7213 19.2952 20.975 18.2892 21.1102C17.2615 21.2484 15.9068 21.25 14 21.25H10C8.09318 21.25 6.73851 21.2484 5.71085 21.1102C4.70476 20.975 4.12511 20.7213 3.7019 20.2981C3.27869 19.8749 3.02502 19.2952 2.88976 18.2892C2.75159 17.2615 2.75 15.9068 2.75 14V12Z").toNodes(),
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.EvenOdd
        )
    }.build()
}
