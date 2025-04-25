// CreateRouteFragment.kt

package pl.pw.epicgameproject

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment


class CreateRouteFragment : Fragment() {

    // Lista do przechowywania zebranych SUROWYCH PUNKTÓW MAPOWYCH
    private val collectedMapPoints = mutableListOf<MapPoint>() // Lista MapPoint

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Nadmuchaj układ dla tego Fragmentu
        val view = inflater.inflate(R.layout.create_route_view, container, false)

        // Znajdź elementy UI
        val editTextRouteName = view.findViewById<EditText>(R.id.edit_text_route_name)
        val editTextCoordX = view.findViewById<EditText>(R.id.edit_text_coord_x)
        val editTextCoordY = view.findViewById<EditText>(R.id.edit_text_coord_y)
        val buttonAddPoint = view.findViewById<Button>(R.id.button_add_point)
        val buttonConfirmRoute = view.findViewById<Button>(R.id.button_confirm_route)

        // --- Skonfiguruj listener dla przycisku "Dodaj punkt" ---
        buttonAddPoint.setOnClickListener {
            val xText = editTextCoordX.text.toString()
            val yText = editTextCoordY.text.toString()

            // Walidacja pól X i Y
            if (xText.isBlank() || yText.isBlank()) {
                Toast.makeText(requireContext(), "Wprowadź obie współrzędne!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Użyj toDoubleOrNull() i odbierz wartości Double
            val xCoord_double = xText.toDoubleOrNull()
            val yCoord_double = yText.toDoubleOrNull()

            // Sprawdź, czy konwersja na Double się udała
            if (xCoord_double != null && yCoord_double != null) {
                // Tworzymy obiekt MapPoint(Double, Double) do przechowywania surowych danych
                val newMapPoint = MapPoint(xCoord_double, yCoord_double) // Tworzymy MapPoint!

                collectedMapPoints.add(newMapPoint) // Dodajemy MapPoint do listy

                // Wyświetl Toast z wartościami Double, żeby potwierdzić precyzję
                Toast.makeText(requireContext(), "Dodano punkt: ($xCoord_double, $yCoord_double)", Toast.LENGTH_SHORT).show()

                // Opcjonalnie: Wyczyść pola po dodaniu punktu
                editTextCoordX.text.clear()
                editTextCoordY.text.clear()

            } else {
                Toast.makeText(requireContext(), "Nieprawidłowe wartości współrzędnych!", Toast.LENGTH_SHORT).show()
            }
        }

        // --- Skonfiguruj listener dla przycisku "Zatwierdź trasę" ---
        buttonConfirmRoute.setOnClickListener {
            val routeName = editTextRouteName.text.toString().trim() // Pobierz nazwę trasy

            // Walidacja: nazwa trasy nie może być pusta
            if (routeName.isBlank()) {
                Toast.makeText(requireContext(), "Podaj nazwę trasy!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Walidacja: musi być przynajmniej jeden punkt
            if (collectedMapPoints.isEmpty()) {
                Toast.makeText(requireContext(), "Dodaj przynajmniej jeden punkt do trasy!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // --- Tworzenie obiektu Route z MapPoint(Double, Double) ---
            // Konwertujemy List<MapPoint> na List<Marker<MapPoint>>
            val markersList: List<Marker> = collectedMapPoints.map { mapPoint ->
                // Dla każdego MapPointa tworzymy nowy Marker z tym MapPointem i domyślnym stanem PENDING
                Marker(point = mapPoint, state = MarkerState.PENDING) // Marker używa MapPoint!
            }

            // Stwórz obiekt Route używając nazwy i listy Markerów (które używają MapPoint)
            val newRoute = Route(name = routeName, markers = markersList) // Route przechowuje Markery z MapPoint!

            // Użyj RouteStorage do zapisania trasy (będzie zapisywał Route z MapPoint)
            // requireContext() daje bezpieczny Context
            RouteStorage.addRoute(requireContext(), newRoute)

            Toast.makeText(requireContext(), "Trasa '$routeName' zapisana pomyślnie (${collectedMapPoints.size} punktów)!", Toast.LENGTH_LONG).show()

            val resultBundle = Bundle().apply {
                putBoolean("route_saved_success", true)
            }
            requireActivity().supportFragmentManager.setFragmentResult("route_saved_key", resultBundle)

            // --- Po zapisaniu zamknij Fragment ---
            requireActivity().supportFragmentManager.popBackStack()

            // Opcjonalnie: Wyślij wynik do Activity, żeby mogła odświeżyć listę tras
            // np. requireActivity().supportFragmentManager.setFragmentResult(...)
        }

        // Zwróć utworzony widok Fragmentu
        return view
    }
    // ... (pozostałe opcjonalne metody Fragmentu) ...
}