// CreateRouteFragment.kt

package pl.pw.epicgameproject

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

private const val PICK_CSV_FILE_FRAGMENT = 3

class CreateRouteFragment : Fragment() {
    private lateinit var buttonImportCsv: Button
    private lateinit var editTextRouteName: EditText
    private lateinit var editTextCoordX: EditText
    private lateinit var editTextCoordY: EditText
    private lateinit var buttonAddPoint: Button
    private lateinit var buttonConfirmRoute: Button

    // Lista do przechowywania zebranych SUROWYCH PUNKTÓW MAPOWYCH
    private val collectedMapPoints = mutableListOf<MapPoint>() // Lista MapPoint

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Nadmuchaj układ dla tego Fragmentu
        val view = inflater.inflate(R.layout.create_route_view, container, false)

        // Znajdź elementy UI
        editTextRouteName = view.findViewById(R.id.edit_text_route_name)
        editTextCoordX = view.findViewById(R.id.edit_text_coord_x)
        editTextCoordY = view.findViewById(R.id.edit_text_coord_y)
        buttonAddPoint = view.findViewById(R.id.button_add_point)
        buttonConfirmRoute = view.findViewById(R.id.button_confirm_route)
        buttonImportCsv = view.findViewById(R.id.button_import_csv)

        // Wywołaj funkcję do ustawiania przycisków
        setUpButtoms()

        return view
    }

    private fun setUpButtoms(){

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

        buttonImportCsv.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "text/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(intent, PICK_CSV_FILE_FRAGMENT)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // Sprawdź, czy rezultat pochodzi z naszego żądania selektora plików z Fragmentu
        if (requestCode == PICK_CSV_FILE_FRAGMENT && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->

                readAndParseCsvFile(uri) // Wywołaj nową metodę do czytania i parsowania

            } ?: run {
                Toast.makeText(requireContext(), "Nie wybrano pliku.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun readAndParseCsvFile(uri: Uri) {
        Log.d("CreateRouteFragment", "Rozpoczynam czytanie i parsowanie pliku CSV z URI: $uri w Fragmentcie.")

        val parsedPoints = mutableListOf<MapPoint>() // Lista do przechowywania wczytanych punktów

        try {
            // Otwórz strumień do odczytu pliku z danego URI
            requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                // Użyj InputStreamReader i BufferedReader do czytania linii tekstu
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    // pomijanie pierwszej linijki csv (nagłówka)
                    val headerLine = reader.readLine()

                    var line: String?
                    var lineNumber = 0 // Licznik linii do lepszego raportowania błędów

                    // Czytaj plik linijka po linijce
                    while (reader.readLine().also { line = it } != null) {
                        lineNumber++
                        val currentLine = line?.trim() ?: "" // Pobierz bieżącą linijkę, usuń białe znaki, użyj pustego stringa jeśli linijka jest null

                        // Pomiń puste linie lub linie komentarzy (np. zaczynające się od #)
                        if (currentLine.isEmpty() || currentLine.startsWith("#")) {
                            continue
                        }

                        // Podziel linijkę na części używając przecinka jako separatora
                        val parts = currentLine.split(",")

                        // Sprawdź, czy linijka ma dokładnie 2 części (X i Y)
                        if (parts.size == 2) {
                            try {
                                // Spróbuj przekonwertować części na liczby Double
                                val x = parts[0].trim().toDouble() // Upewnij się, że usuwasz białe znaki przed konwersją
                                val y = parts[1].trim().toDouble()

                                val newPoint = MapPoint(x, y) //zakładamy, że CSV jest w formacie (X, Y)
                                parsedPoints.add(newPoint) // Dodaj poprawnie sparsowany punkt do listy
                                Log.d("CreateRouteFragment", "Linia $lineNumber: Parsowano punkt: ($x, $y)")

                            } catch (e: NumberFormatException) {
                                // Błąd konwersji na liczbę
                                Log.w("CreateRouteFragment", "Linia $lineNumber: Błąd parsowania liczby: ${e.message}. Linijka: '$currentLine'")
                                // Możesz pominąć tę linię lub powiadomić użytkownika
                            }
                        } else {
                            // Linijka nie ma 2 części
                            Log.w("CreateRouteFragment", "Linia $lineNumber: Nieoczekiwana liczba części (${parts.size}). Oczekiwano 2. Linijka: '$currentLine'")
                            // Możesz pominąć tę linię lub powiadomić użytkownika
                        }
                    }
                }
            }

            // --- ZAKOŃCZONO CZYTANIE PLIKU ---

            // Sprawdź, czy wczytano jakiekolwiek punkty
            if (parsedPoints.isEmpty()) {
                Log.w("CreateRouteFragment", "Brak poprawnie sparsowanych punktów z pliku CSV.")
                Toast.makeText(requireContext(), "Plik CSV jest pusty lub w niepoprawnym formacie.", Toast.LENGTH_LONG).show()
                // Fragment pozostaje otwarty, użytkownik może spróbować ponownie lub wprowadzić ręcznie.
                return // Przerywamy proces, jeśli brak punktów
            }

            Log.d("CreateRouteFragment", "Pomyślnie sparsowano ${parsedPoints.size} punktów z pliku CSV.")


            // --- Pobierz nazwę pliku z URI ---
            val defaultRouteName = getFileNameFromUri(uri) ?: "Trasa z CSV" // Pobierz nazwę lub użyj domyślnej
            // Opcjonalnie: usuń rozszerzenie ".csv" z nazwy pliku
            val routeNameWithoutExtension = defaultRouteName.removeSuffix(".csv")

            // --- Zapytaj użytkownika o nazwę trasy ---
            showRouteNameDialog(parsedPoints, routeNameWithoutExtension)

        } catch (e: IOException) {
            // Błąd podczas otwierania lub czytania pliku
            Log.e("CreateRouteFragment", "Błąd odczytu pliku CSV: ${e.message}", e)
            Toast.makeText(requireContext(), "Błąd odczytu pliku CSV: ${e.message}", Toast.LENGTH_LONG).show()
            // Fragment pozostaje otwarty.
        } catch (e: Exception) {
            // Inne nieoczekiwane błędy podczas parsowania
            Log.e("CreateRouteFragment", "Nieoczekiwany błąd podczas parsowania CSV: ${e.message}", e)
            Toast.makeText(requireContext(), "Błąd podczas przetwarzania pliku CSV.", Toast.LENGTH_LONG).show()
            // Fragment pozostaje otwarty.
        }
    }


    private fun getFileNameFromUri(uri: Uri): String? {
        Log.d("CreateRouteFragment", "Próbuję pobrać nazwę pliku z URI: $uri")
        val contentResolver: ContentResolver = requireContext().contentResolver
        // Zapytanie do ContentResolver o informacje o URI
        val cursor = contentResolver.query(uri, null, null, null, null)

        cursor?.use { // Użyj use do automatycznego zamknięcia kursora
            // Przejdź do pierwszego (i zazwyczaj jedynego) wiersza rezultatu
            if (it.moveToFirst()) {
                // Znajdź indeks kolumny z nazwą wyświetlaną
                // OpenableColumns.DISPLAY_NAME to standardowa kolumna dla nazwy wyświetlanej pliku w ContentProviderach obsługujących ACTION_OPEN_DOCUMENT
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                // Sprawdź, czy kolumna DISPLAY_NAME istnieje (-1 jeśli nie istnieje)
                if (nameIndex != -1) {
                    val fileName = it.getString(nameIndex)
                    Log.d("CreateRouteFragment", "Pobrano nazwę pliku z URI: $fileName")
                    return fileName // Zwróć nazwę pliku
                } else {
                    Log.w("CreateRouteFragment", "Nie znaleziono kolumny DISPLAY_NAME dla URI.")
                }
            } else {
                Log.w("CreateRouteFragment", "Kursor dla URI pusty.")
            }
        } ?: run {
            Log.w("CreateRouteFragment", "ContentResolver.query() zwrócił null dla URI.")
        }

        Log.w("CreateRouteFragment", "Nie udało się pobrać nazwy pliku z URI.")
        return null // Zwróć null, jeśli nie udało się pobrać nazwy
    }

    private fun showRouteNameDialog(points: List<MapPoint>, defaultName: String) {
        Log.d("CreateRouteFragment", "Pokazuję dialog zapytania o nazwę dla trasy z CSV.")

        val inputEditText = EditText(requireContext()).apply {
            hint = "Wprowadź nazwę trasy"
            setText(defaultName)
            setSelection(defaultName.length)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Podaj nazwę dla importowanej trasy")
            .setView(inputEditText) // Ustaw EditText jako widok dialogu
            .setPositiveButton("Zatwierdź") { dialog, which ->
                val routeName = inputEditText.text.toString().trim()

                // --- Logika po zatwierdzeniu nazwy ---
                if (routeName.isEmpty()) {
                    Toast.makeText(requireContext(), "Nazwa trasy nie może być pusta.", Toast.LENGTH_SHORT).show()
                    // Możesz ponownie pokazać dialog lub zostawić Fragment otwarty.
                    // Nie zamykamy dialogu automatycznie, jeśli nazwa jest pusta.
                    // Aby dialog pozostał otwarty na pustej nazwie, trzeba zastosować niestandardowy listener przycisku pozytywnego.
                    // Na razie Toast i pozwalamy dialogowi się zamknąć.
                    return@setPositiveButton // Przerywamy, jeśli nazwa pusta
                }

                // --- Stwórz obiekt Route z wczytanych punktów ---
                val markersList: List<Marker> = points.map { mapPoint ->
                    Marker(point = mapPoint, state = MarkerState.PENDING) // Wszystkie punkty początkowo PENDING
                }
                val newRoute = Route(name = routeName, markers = markersList)

                // --- Zapisz trasę do pamięci trwałej ---
                try {
                    // TODO: Upewnij się, że RouteStorage.addRoute jest dostępne i działa poprawnie w kontekście Fragmentu.
                    // Może być potrzebne przekazanie go z Activity lub inna adaptacja.
                    // Na razie zakładam, że jest dostępne.
                    RouteStorage.addRoute(requireContext(), newRoute)

                    Log.i("CreateRouteFragment", "Trasa '$routeName' zaimportowana i zapisana pomyślnie (${points.size} punktów)!")
                    Toast.makeText(requireContext(), "Trasa '$routeName' zaimportowana i zapisana!", Toast.LENGTH_LONG).show()

                    // --- Zakończ Fragment i wróć do Activity ---
                    // Wyślij rezultat z powrotem do Activity, żeby odświeżyło listę tras.
                    val resultBundle = Bundle().apply {
                        putBoolean("route_saved_success", true) // Ten sam klucz co przy ręcznym dodawaniu
                    }
                    requireActivity().supportFragmentManager.setFragmentResult("route_saved_key", resultBundle)
                    Log.d("CreateRouteFragment", "Wysłano rezultat fragmentu po imporcie CSV.")

                    // Zamknij Fragment
                    // Używamy opóźnienia jak przy ręcznym dodawaniu, żeby uniknąć problemów z timingiem
                    Log.d("CreateRouteFragment", "Przygotowanie do popBackStack() po imporcie CSV...")
                    Handler(Looper.getMainLooper()).postDelayed({
                        Log.d("CreateRouteFragment", "Wywołanie popBackStack() po opóźnieniu (CSV).")
                        requireActivity().supportFragmentManager.popBackStack()
                    }, 100) // Opóźnienie 100 ms


                } catch (e: Exception) {
                    Log.e("CreateRouteFragment", "Błąd podczas zapisu importowanej trasy '$routeName'.", e)
                    Toast.makeText(requireContext(), "Błąd zapisu importowanej trasy!", Toast.LENGTH_LONG).show()
                    // Fragment pozostaje otwarty.
                }

                // dialog.dismiss() // Dialog zamyka się automatycznie po kliknięciu przycisku.
            }
            .setNegativeButton("Anuluj", null) // Przycisk Anuluj
            .show()
    }

}