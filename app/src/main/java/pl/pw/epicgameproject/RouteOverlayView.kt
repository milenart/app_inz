package pl.pw.epicgameproject

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View

// --- Struktury Danych (możesz je przenieść do osobnego pliku np. RouteData.kt) ---

// Reprezentuje punkt na obrazie (współrzędne X, Y w pikselach)
data class Point(val x: Float, val y: Float)

// Definiuje możliwe stany markera
enum class MarkerState {
    PENDING, // Oczekujący (np. czerwony)
    CURRENT, // Aktualny (np. żółty/pomarańczowy)
    VISITED  // Odwiedzony (np. zielony)
}

// Reprezentuje pojedynczy marker na trasie
// Zawiera punkt (lokalizację) i jego aktualny stan
data class Marker(val point: Point, var state: MarkerState = MarkerState.PENDING)

// Reprezentuje całą trasę
data class Route(val name: String, val markers: List<Marker>)

// --- Niestandardowy Widok do Rysowania Trasy ---

class RouteOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Przechowuje aktualnie wyświetlaną trasę
    private var currentRoute: Route? = null
    // Definiuje promień rysowanych markerów (kółek) w pikselach
    private val markerRadius = 15f

    // Obiekty Paint definiują jak rysować (kolor, grubość, styl)
    // Inicjalizujemy je raz, aby nie tworzyć ich ciągle w onDraw
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#42A5F5") // Jasnoniebieski kolor linii
        style = Paint.Style.STROKE // Rysuj tylko kontur
        strokeWidth = 8f          // Grubość linii
        strokeCap = Paint.Cap.ROUND // Zaokrąglone końce linii
    }

    private val pendingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED          // Czerwony dla oczekujących
        style = Paint.Style.FILL   // Wypełnij kółko
    }

    private val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFA000") // Pomarańczowy dla aktualnego
        style = Paint.Style.FILL
    }

    private val visitedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50") // Zielony dla odwiedzonych
        style = Paint.Style.FILL
    }

    /**
     * Publiczna metoda do ustawiania trasy, która ma być narysowana.
     * Wywołaj tę metodę z Activity/Fragment, przekazując obiekt Route.
     * Wywołanie tej metody (nawet z tym samym obiektem Route, ale po zmianie
     * stanu jego markerów) spowoduje żądanie przerysowania widoku.
     *
     * @param route Trasa do wyświetlenia lub null, aby wyczyścić widok.
     */
    fun setRoute(route: Route?) {
        this.currentRoute = route
        // WAŻNE: Ta linia mówi systemowi Android, że zawartość tego widoku
        // się zmieniła i musi on zostać przerysowany. Bez tego zmiany nie
        // będą widoczne na ekranie!
        invalidate()
    }



    /**
     * Główna metoda odpowiedzialna za rysowanie zawartości widoku.
     * System Android wywołuje ją, gdy widok musi zostać narysowany
     * (np. po wywołaniu invalidate() lub przy pierwszym pojawieniu się widoku).
     *
     * @param canvas Płótno (Canvas), na którym rysujemy.
     */
    override fun onDraw(canvas: Canvas) {
        // super.onDraw(canvas) // Wywołanie metody z klasy nadrzędnej (View)

        // Rysuj cokolwiek tylko wtedy, gdy mamy ustawioną jakąś trasę
        currentRoute?.let { route ->
            val markers = route.markers

            // Sprawdzenie, czy jest co najmniej 2 markery, aby narysować linie
            if (markers.size >= 2) {
                // 1. Rysuj linie łączące kolejne markery
                // Iterujemy od drugiego markera (indeks 1)
                for (i in 1 until markers.size) {
                    val startPoint = markers[i - 1].point // Punkt początkowy linii
                    val endPoint = markers[i].point       // Punkt końcowy linii
                    canvas.drawLine(startPoint.x, startPoint.y, endPoint.x, endPoint.y, linePaint)
                }
            }

            // 2. Rysuj markery (kółka) na wierzchu linii
            // Iterujemy po wszystkich markerach
            markers.forEach { marker ->
                // Wybierz odpowiedni 'Paint' (kolor i styl) w zależności od stanu markera
                val paint = when (marker.state) {
                    MarkerState.PENDING -> pendingPaint
                    MarkerState.CURRENT -> currentPaint
                    MarkerState.VISITED -> visitedPaint
                }
                // Narysuj kółko w miejscu markera z wybranym kolorem
                canvas.drawCircle(marker.point.x, marker.point.y, markerRadius, paint)
            }
        }
    }
}