// W pliku: RouteOverlayView.kt

package pl.pw.epicgameproject // Upewnij się, że pakiet jest prawidłowy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.view.View

private const val TAG_OVERLAY = "RouteOverlayView"

// --- Niestandardowy Widok do Rysowania Trasy (używający ScreenPoint) ---

class RouteOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Przechowujemy listę Markerów dla bieżącego piętra
    private var routeMarkers: List<Marker> = emptyList()
    // Przechowujemy index aktualnego celu W RAMACH listy markerów dla bieżącego piętra
    private var currentTargetIndex: Int = -1
    private var lastMarkerIndexOnCurrentFloor: Int = -1 // Index ostatniego markera na tym piętrze (w ramach routeMarkers)
    private var totalRouteMarkerCount: Int = 0

    private var mapConverter: MapConverter? = null
    // marker z poprzedniego piętra
    private var previousFloorLastMarker: Marker? = null

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
        color = Color.parseColor("#FFA500")
        style = Paint.Style.FILL
    }

    private val visitedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        strokeWidth = 8f
    }

    private val lastOnFloorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.MAGENTA
        style = Paint.Style.FILL
    }

    private val endPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }


    private val markerRadius = 15f // Promień punktów


    /**
     * Ustawia DANE DO RYSOWANIA (listę markerów dla bieżącego piętra),
     * index celu W TEJ liście, ORAZ instancję MapConvertera.
     */
    // ZMIEŃ SYGNATURĘ METODY - DODAJ MapConverter jako trzeci parametr
    fun setRouteAndStates(
        markersOnCurrentFloor: List<Marker>, // Markery tylko dla tego piętra
        currentTargetIndexOnCurrentFloor: Int, // Index celu W TEJ liście
        mapConverter: MapConverter,
        lastMarkerIndexOnCurrentFloor: Int, // Index ostatniego na piętrze W TEJ liście
        totalRouteMarkerCount: Int,
        previousFloorLastMarker: Marker?
    ) {
        Log.d(TAG_OVERLAY, "setRouteAndStates called. Markers on current floor: ${markersOnCurrentFloor.size}, target index on floor: $currentTargetIndexOnCurrentFloor, last on floor index: $lastMarkerIndexOnCurrentFloor, total route markers: $totalRouteMarkerCount")
        this.routeMarkers = markersOnCurrentFloor // Zapisz listę markerów na tym piętrze
        this.currentTargetIndex = currentTargetIndexOnCurrentFloor // Zapisz index celu w tej liście
        this.mapConverter = mapConverter // Zapisz instancję Konwertera
        this.lastMarkerIndexOnCurrentFloor = lastMarkerIndexOnCurrentFloor // Zapisz index ostatniego na piętrze w tej liście
        this.totalRouteMarkerCount = totalRouteMarkerCount // Zapisz całkowitą liczbę markerów w trasie
        this.previousFloorLastMarker = previousFloorLastMarker

        // invalidate() // Wywołaj invalidate po ustawieniu danych (może być wywołane z zewnątrz)
    }

    /**
     * Metoda do wyczyszczenia rysowanej trasy.
     */
    fun clearRoute() {
        Log.d(TAG_OVERLAY, "clearRoute called.")
        this.routeMarkers = emptyList() // Wyczyść listę markerów
        this.currentTargetIndex = -1 // Zresetuj index celu
        this.mapConverter = null // Wyczyść referencję do konwertera
        this.lastMarkerIndexOnCurrentFloor = -1 // Zresetuj index ostatniego na piętrze
        this.totalRouteMarkerCount = 0 // Zresetuj licznik
        this.previousFloorLastMarker = null
        // invalidate() // Wywołaj invalidate (może być wywołane z zewnątrz)
    }


    /**
     * Metoda rysująca - używa przechowywanych Markerów i MapConvertera do przeliczeń.
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        Log.d(TAG_OVERLAY, "onDraw called. Rysuję ${routeMarkers.size} markerów na obecnym piętrze.")
        // Dodaj logowanie o poprzednim punkcie, jeśli jest
        if (previousFloorLastMarker != null) {
            Log.d(TAG_OVERLAY, "onDraw: Dostępny ostatni punkt poprzedniego piętra: ${previousFloorLastMarker?.point?.floor}")
        } else {
            Log.d(TAG_OVERLAY, "onDraw: Brak ostatniego punktu poprzedniego piętra do narysowania.")
        }


        // Sprawdź, czy mamy listę markerów (na tym piętrze) I czy MapConverter jest dostępny
        // Rysujemy, jeśli mamy markery na bieżącym piętrze LUB jeśli mamy punkt z poprzedniego piętra DO narysowania.
        if (routeMarkers.isEmpty() && previousFloorLastMarker == null || mapConverter == null) {
            Log.d(TAG_OVERLAY, "onDraw: Brak markerów (na tym piętrze i z poprzedniego piętra) do narysowania LUB mapConverter jest nullem.")
            return // Nic do narysowania
        }


        // --- Rysowanie Linii Segment po Segmencie ---
        // Rysujemy linie między kolejnymi punktami (markerami) w liście routeMarkers (tylko dla tego piętra).
        // Jeśli dostępny jest punkt poprzedniego piętra, pierwsza linia powinna być od niego do pierwszego punktu obecnego piętra.
        // Musimy uwzględnić ten "dodatkowy" punkt początkowy dla pierwszej linii.

        // Iterujemy przez punkty końcowe segmentów. Pierwszy segment może zaczynać się od previousFloorLastMarker.
        // Iterujemy od indexu 0 do ostatniego indexu w routeMarkers.
        for (i in routeMarkers.indices) { // Iteruj przez wszystkie indexy punktów NA TYM PIĘTRZE

            val startMarker: Marker // Marker, od którego zaczyna się segment
            val endMarker: Marker = routeMarkers[i] // Marker, na którym kończy się segment (zawsze punkt z routeMarkers)

            if (i == 0 && previousFloorLastMarker != null) {
                // Pierwszy segment na tym piętrze - zaczyna się od ostatniego punktu poprzedniego piętra
                startMarker = previousFloorLastMarker!! // Punkt początkowy segmentu to ostatni z poprzedniego piętra
                Log.d(TAG_OVERLAY, "onDraw: Rysuję pierwszy segment na piętrze z punktu poprzedniego piętra do ${endMarker.point}.")

            } else if (i > 0) {
                // Segmenty wewnątrz piętra - zaczynają się od poprzedniego markera na tym piętrze.
                startMarker = routeMarkers[i - 1] // Punkt początkowy segmentu (poprzedni w liście na tym piętrze)
                Log.d(TAG_OVERLAY, "onDraw: Rysuję segment wewnątrz piętra z ${startMarker.point} do ${endMarker.point}.")
            } else {
                // Przypadek i == 0 i previousFloorLastMarker == null.
                // Oznacza, że pierwszy punkt na obecnym piętrze jest jednocześnie pierwszy punktem CAŁEJ trasy,
                // lub jest to pierwsze piętro widoczne, ale nie ma punktów wcześniej.
                // W tym przypadku nie ma linii prowadzącej DO tego pierwszego punktu z poprzedniego.
                Log.d(TAG_OVERLAY, "onDraw: Pomijam rysowanie segmentu do pierwszego punktu piętra (index 0) - brak poprzedniego punktu piętra.")
                continue // Pomiń rysowanie segmentu DO pierwszego punktu na liście, jeśli brak poprzedniego punktu piętra
            }


            // Przelicz punkty początkowy i końcowy segmentu na piksele ekranu
            val startBitmapPixelPoint = mapConverter!!.mapToPixel(startMarker.point)
            val startScreenPoint = mapConverter!!.convertToScreenCoordinates(startBitmapPixelPoint, this.width, this.height)

            val endBitmapPixelPoint = mapConverter!!.mapToPixel(endMarker.point)
            val endScreenPoint = mapConverter!!.convertToScreenCoordinates(endBitmapPixelPoint, this.width, this.height)

            // Jeśli oba punkty segmentu zostały poprawnie przeliczone na piksele ekranu
            if (startScreenPoint != null && endScreenPoint != null) {
                // --- KOLOROWANIE LINII ---
                // Linia jest ZIELONA tylko, jeśli OBA punkty końcowe segmentu są odwiedzone (lub końcowe).

                // Sprawdź, czy PUNKT POCZĄTKOWY segmentu jest odwiedzony (lub końcowy).
                // Jeśli startMarker to previousFloorLastMarker, jego stan (VISITED) jest używany.
                val isStartVisited = startMarker.state == MarkerState.VISITED || startMarker.state == MarkerState.END
                // Sprawdź, czy PUNKT KOŃCOWY segmentu jest odwiedzony (lub końcowy).
                val isEndVisited = endMarker.state == MarkerState.VISITED || endMarker.state == MarkerState.END

                val currentLinePaint = if (isStartVisited && isEndVisited) {
                    visitedPaint // Linia jest ZIELONA jeśli oba końce są odwiedzone/końcowe
                } else {
                    linePaint // W przeciwnym razie użyj domyślnego pędzla dla linii (niebieski)
                    // Ta linia będzie prowadzić do punktu CURRENT lub PENDING
                }

                // Rysuj linię między punktem początkowym a końcowym segmentu
                canvas.drawLine(startScreenPoint.x, startScreenPoint.y, endScreenPoint.x, endScreenPoint.y, currentLinePaint) // <-- RYSOWANIE LINII

            } else {
                Log.w(TAG_OVERLAY, "onDraw: Nie udało się przeliczyć segmentu linii na piksele ekranu. Start: ${startMarker.point}, End: ${endMarker.point}.")
            }
        }


        // --- Rysowanie Punktów ---
        // Rysujemy punkty PO narysowaniu linii, żeby były na wierzchu.

        // Rysowanie OSTATNIEGO PUNKTU POPRZEDNIEGO PIĘTRA (powtarzamy, żeby był NAD liniami, jeśli linia do niego dochodzi/od niego wychodzi)
        // Możemy pominąć to powtórne rysowanie, jeśli linia wychodzi od niego i punkt sam w sobie jest rysowany na swoim piętrze.
        // Ale skoro użytkownik chce go widzieć na nowym piętrze, rysujemy go tu.
        // Najlepiej było narysować go raz NA POCZĄTKU, PRZED WSZYSTKIMI LINIAMI I PUNKTAMI.
        // Powtórzone rysowanie tutaj zapewni, że jest na wierzchu, ale jest mniej optymalne.
        // Zgodnie z Etapem 11.1, rysujemy go NA POCZĄTKU, PRZED LINIAMI. Usuniemy stąd powtórzenie.

        if (previousFloorLastMarker != null) {
             val bitmapPixelPoint = mapConverter!!.mapToPixel(previousFloorLastMarker!!.point)
             val screenPoint = mapConverter!!.convertToScreenCoordinates(bitmapPixelPoint, this.width, this.height)
             if (screenPoint != null) {
                  canvas.drawCircle(screenPoint.x, screenPoint.y, markerRadius, visitedPaint) // Zawsze jako odwiedzony
             }
        }

        // Punkt poprzedniego piętra jest już rysowany NA POCZĄTKU onDraw.


        for (i in routeMarkers.indices) { // Iteruj przez wszystkie punkty NA TYM PIĘTRZE (tylko z routeMarkers)
            val marker = routeMarkers[i] // Pobierz bieżący marker

            // Przelicz punkt markera na piksele ekranu
            val bitmapPixelPoint = mapConverter!!.mapToPixel(marker.point)
            val screenPoint = mapConverter!!.convertToScreenCoordinates(bitmapPixelPoint, this.width, this.height)

            // Jeśli konwersja się powiodła
            if (screenPoint != null) {
                // --- WYBÓR PĘDZLA DLA PUNKTU (z priorytetami) ---
                // Ta logika pozostaje w dużej mierze taka sama, bazując na stanach w Marker.state
                // i indexie ostatniego punktu NA TYM PIĘTRZE.
                val pointPaint = when {
                    // 1. Najwyższy priorytet: OSTATNI PUNKT CAŁEJ TRASY
                    marker.state == MarkerState.END -> {
                        Log.d(TAG_OVERLAY, "onDraw: Rysuję marker ${i} (index na piętrze) jako END (Czarny).")
                        endPointPaint // Czarny dla końca trasy
                    }
                    // 2. Średni priorytet: OSTATNI PUNKT NA TYM PIĘTRZE
                    i == lastMarkerIndexOnCurrentFloor -> { // Sprawdź, czy to ostatni punkt w PRZEFILTROWANEJ liście dla tego piętra
                        Log.d(TAG_OVERLAY, "onDraw: Rysuję marker ${i} (index na piętrze) jako LAST_ON_FLOOR (Fioletowy). Jego stan to ${marker.state}.")
                        lastOnFloorPaint // Fioletowy dla ostatniego na piętrze
                    }
                    // 3. Najniższy priorytet (podstawowe stany nawigacji)
                    marker.state == MarkerState.VISITED -> {
                        Log.d(TAG_OVERLAY, "onDraw: Rysuję marker ${i} (index na piętrze) jako VISITED (Zielony).")
                        visitedPaint // Zielony
                    }
                    marker.state == MarkerState.CURRENT -> {
                        Log.d(TAG_OVERLAY, "onDraw: Rysuję marker ${i} (index na piętrze) jako CURRENT (Czerwony).")
                        currentPaint // Czerwony
                    }
                    else -> { // MarkerState.PENDING
                        Log.d(TAG_OVERLAY, "onDraw: Rysuję marker ${i} (index na piętrze) jako PENDING (Niebieski).")
                        pendingPaint // Niebieski (lub inny domyślny dla oczekujących)
                    }
                }

                // Rysuj kółko (punkt)
                canvas.drawCircle(screenPoint.x, screenPoint.y, markerRadius, pointPaint) // <-- RYSOWANIE PUNKTU


            } else {
                Log.w(TAG_OVERLAY, "onDraw: Konwersja BIEŻĄCEGO punktu marker[${i}] (${marker.point}) na ekran nie powiodła się. Nie rysuję punktu.")
            }
        }


        Log.d(TAG_OVERLAY, "onDraw: Zakończono rysowanie markerów i linii.")
    }
}