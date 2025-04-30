package pl.pw.epicgameproject

import android.graphics.Bitmap // For Bitmap dimensions
import android.util.Log // For logging

data class WorldFileParameters(
    val paramA: Double,
    val paramB: Double,
    val paramC: Double,
    val paramD: Double,
    val paramE: Double,
    val paramF: Double
)

class MapConverter(
    private val pgwParams: WorldFileParameters,
    private val bitmapWidth: Int, // Constant bitmap width for all floors
    private val bitmapHeight: Int // Constant bitmap height for all floors
) {

    private val TAG = "MapConverter"

    fun mapToPixel(mapPoint: MapPoint): ScreenPoint? {

        val mapX = mapPoint.x
        val mapY = mapPoint.y

        Log.d(TAG, "mapToPixel: Wejście (geoX, geoY) = ($mapX, $mapY) dla piętra ${mapPoint.floor}") // Logowanie wejściowych współrzędnych

        // Use the stored PGW parameters
        val denominator = pgwParams.paramA * pgwParams.paramE - pgwParams.paramB * pgwParams.paramD
        if (denominator == 0.0) {
            Log.e(TAG, "mapToPixel: Denominator is zero, cannot perform conversion.")
            return null // Avoid division by zero
        }
        Log.d(TAG, "mapToPixel: PGW Params: A=${pgwParams.paramA}, B=${pgwParams.paramB}, C=${pgwParams.paramC}, D=${pgwParams.paramD}, E=${pgwParams.paramE}, F=${pgwParams.paramF}")
        Log.d(TAG, "mapToPixel: Denominator = $denominator")

        val pixelX = (pgwParams.paramE * (mapX - pgwParams.paramC) - pgwParams.paramB * (mapY - pgwParams.paramF)) / denominator
        val pixelY = (pgwParams.paramA * (mapY - pgwParams.paramF) - pgwParams.paramD * (mapX - pgwParams.paramC)) / denominator

        Log.d(TAG, "mapToPixel: Wyjście (pixelX, pixelY) = ($pixelX, $pixelY)") // Logowanie wyjściowych współrzędnych

        return ScreenPoint(pixelX.toFloat(), pixelY.toFloat())
    }

    // Function to convert pixel coordinates on the BITMAP to pixel coordinates on the SCREEN/VIEW
    // Uses the stored bitmap dimensions and the view's dimensions.
    // This function is called from RouteOverlayView.onDraw, so it needs view dimensions.
    fun convertToScreenCoordinates(bitmapPixel: ScreenPoint?, viewWidth: Int, viewHeight: Int): ScreenPoint? {
        if (bitmapPixel == null) {
            Log.w(TAG, "convertToScreenCoordinates: Input bitmapPixel is null.")
            return null
        }

        val imageWidth = this.bitmapWidth.toFloat()
        val imageHeight = this.bitmapHeight.toFloat()
        val viewWidthFloat = viewWidth.toFloat()
        val viewHeightFloat = viewHeight.toFloat()

        if (imageWidth <= 0 || imageHeight <= 0 || viewWidthFloat <= 0 || viewHeightFloat <= 0) {
            Log.e(TAG, "convertToScreenCoordinates: Invalid dimensions (image or view).")
            return null // Avoid division by zero or invalid calculations
        }

        // Calculate scaling factors
        val scaleX = viewWidthFloat / imageWidth
        val scaleY = viewHeightFloat / imageHeight

        // Apply scaling and translation from image pixels to view pixels
        // Assuming scaleType="centerInside" in ImageView
        val scaledImageWidth = imageWidth * Math.min(scaleX, scaleY)
        val scaledImageHeight = imageHeight * Math.min(scaleX, scaleY)

        val offsetX = (viewWidthFloat - scaledImageWidth) / 2f
        val offsetY = (viewHeightFloat - scaledImageHeight) / 2f

        val screenX = bitmapPixel.x * Math.min(scaleX, scaleY) + offsetX
        val screenY = bitmapPixel.y * Math.min(scaleX, scaleY) + offsetY

        return ScreenPoint(screenX, screenY)
    }

    // You might also want a function to get the PGW parameters if needed elsewhere
    fun getPgwParameters(): WorldFileParameters {
        return pgwParams
    }

    // And bitmap dimensions if needed
    fun getBitmapDimensions(): Pair<Int, Int> {
        return Pair(bitmapWidth, bitmapHeight)
    }
}