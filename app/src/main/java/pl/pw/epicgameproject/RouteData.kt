// RouteData.kt

package pl.pw.epicgameproject // Upewnij się, że pakiet jest prawidłowy

// Definiuje możliwe stany markera (bez zmian)
enum class MarkerState {
    PENDING,
    CURRENT,
    VISITED
}

// --- KLASY PUNKTÓW ---

// Reprezentuje punkt w UKŁADZIE MAPOWYM (np. z pliku PGW).
// Używa Double dla precyzji, żeby przechowywać surowe dane mapowe.
data class MapPoint(val x: Double, val y: Double)

// Reprezentuje punkt w PIKSELach na ekranie/widoku RouteOverlayView.
// Używa Float, jak wymagają funkcje Canvas do rysowania.
data class ScreenPoint(val x: Float, val y: Float)


// --- KLASY TRASY (używające MapPoint dla surowych danych) ---

// Reprezentuje pojedynczy marker na trasie w UKŁADZIE MAPOWYM.
// Jego punkt jest MapPoint.
data class Marker(val point: MapPoint, var state: MarkerState = MarkerState.PENDING)

// Reprezentuje całą trasę. Przechowuje listę Markerów (które używają MapPoint).
data class Route(val name: String, val markers: List<Marker>)