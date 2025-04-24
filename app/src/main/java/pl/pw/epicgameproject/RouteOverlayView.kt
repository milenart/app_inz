// RouteOverlayView.kt

package pl.pw.epicgameproject // Upewnij się, że pakiet jest prawidłowy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View


// --- Niestandardowy Widok do Rysowania Trasy (używający ScreenPoint) ---

class RouteOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // --- DANE DO RYSOWANIA ---
    // Przechowuje listę punktów do narysowania W PIKSELACH EKRANU (ScreenPoint)
    private var drawingPixelPoints: List<ScreenPoint> = emptyList()
    // Przechowuje listę stanów markerów (musi odpowiadać liście punktów)
    private var drawingMarkerStates: List<MarkerState> = emptyList()


    // --- Obiekty Paint (pozostają bez zmian) ---
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#42A5F5")
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }

    private val pendingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
    }

    private val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFA000")
        style = Paint.Style.FILL
    }

    private val visitedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.FILL
    }

    private val markerRadius = 15f


    /**
     * Ustawia DANE DO RYSOWANIA (w pikselach ekranu).
     * Przekazuj listy ScreenPoint i MarkerState PO wykonaniu konwersji w MainActivity.
     */
    fun setScreenRouteData(pixelPoints: List<ScreenPoint>, states: List<MarkerState>) {
        // Zapisujemy gotowe punkty pikselowe i stany
        this.drawingPixelPoints = pixelPoints
        this.drawingMarkerStates = states
        // KAŻDYM razem, gdy zmieniasz dane do narysowania, MUSISZ wywołać invalidate()
        invalidate()
    }

    /**
     * Metoda do wyczyszczenia rysowanej trasy.
     */
    fun clearRoute() {
        this.drawingPixelPoints = emptyList()
        this.drawingMarkerStates = emptyList()
        invalidate()
    }


    /**
     * Metoda rysująca - używa gotowych punktów pikselowych (ScreenPoint).
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas) // WAŻNE: Wywołaj metodę z klasy nadrzędnej (View)

        if (drawingPixelPoints.isNotEmpty()) {
            val points = drawingPixelPoints // Lista gotowych ScreenPoint

            // 1. Rysuj linie łączące kolejne punkty (ScreenPoint)
            if (points.size >= 2) {
                for (i in 1 until points.size) {
                    val startPoint = points[i - 1] // ScreenPoint
                    val endPoint = points[i]       // ScreenPoint
                    // Użyj ScreenPoint.x i ScreenPoint.y (które są Float) do rysowania
                    canvas.drawLine(startPoint.x, startPoint.y, endPoint.x, endPoint.y, linePaint)
                }
            }

            // 2. Rysuj markery (kółka) na wierzchu linii, używając punktów ScreenPoint
            points.forEachIndexed { index, pixelPoint ->
                val state = drawingMarkerStates.getOrNull(index) ?: MarkerState.PENDING

                val paint = when (state) {
                    MarkerState.PENDING -> pendingPaint
                    MarkerState.CURRENT -> currentPaint
                    MarkerState.VISITED -> visitedPaint
                }
                // Użyj ScreenPoint.x i ScreenPoint.y (które są Float) do rysowania
                canvas.drawCircle(pixelPoint.x, pixelPoint.y, markerRadius, paint)
            }
        }
    }
}