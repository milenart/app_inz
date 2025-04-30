// CreateRouteFragment.kt

package pl.pw.epicgameproject

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentResolver
import android.content.Context
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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.fragment.app.Fragment
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

private const val PICK_CSV_FILE_FRAGMENT = 3
private const val TAG_FRAGMENT = "CreateRouteFragment" // Tag do logowania we Fragmentcie

class CreateRouteFragment : Fragment() {

    private lateinit var buttonImportCsv: Button
    private lateinit var editTextRouteName: EditText
    private lateinit var editTextCoordX: EditText
    private lateinit var editTextCoordY: EditText
    private lateinit var buttonAddPoint: Button
    private lateinit var buttonConfirmRoute: Button
    private lateinit var spinnerFloor: Spinner // Spinner wyboru piętra

    private var availableFloors: IntArray = intArrayOf() // Tablica dostępnych pięter przekazana z Activity

    // Lista do przechowywania zebranych markerów trasy (ręcznie lub z CSV) przed zapisem
    private val tempRouteMarkers = mutableListOf<Marker>() // <-- Zmieniono nazwę na bardziej jasną i usunięto collectedMapPoints


    // onCreateView: Nadmuchuje layout Fragmentu.
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Nadmuchaj layout dla tego fragmentu
        return inflater.inflate(R.layout.create_route_view, container, false)
    }

    // onViewCreated: Inicjalizuje widoki i ustawia listenery po utworzeniu widoku.
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG_FRAGMENT, "onViewCreated called")

        // --- Znajdź widoki z layoutu ---
        buttonImportCsv = view.findViewById(R.id.button_import_csv)
        editTextRouteName = view.findViewById(R.id.edit_text_route_name)
        editTextCoordX = view.findViewById(R.id.edit_text_coord_x)
        editTextCoordY = view.findViewById(R.id.edit_text_coord_y)
        buttonAddPoint = view.findViewById(R.id.button_add_point)
        buttonConfirmRoute = view.findViewById(R.id.button_confirm_route)
        spinnerFloor = view.findViewById(R.id.spinner_floor) // Znajdź Spinner piętra


        // --- Odczytaj argumenty (dostępne piętra) przekazane z Activity ---
        arguments?.getIntArray("available_floors")?.let {
            availableFloors = it // Zapisz dostępne piętra

            // Utwórz ArrayAdapter dla Spinnera pięter
            val floorStrings = availableFloors.map { it.toString() }.toTypedArray() // Skonwertuj IntArray na Array<String>
            val floorArrayAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, floorStrings)
            floorArrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) // Ustaw layout dla listy rozwijanej
            spinnerFloor.adapter = floorArrayAdapter // Przypisz adapter do Spinnera
            Log.d(TAG_FRAGMENT, "Spinner pięter zainicjalizowany z opcjami: ${availableFloors.joinToString()}")

            // Opcjonalnie: Ustaw domyślne wybrane piętro w Spinnerze (np. 0 lub pierwszy element)
            val defaultFloorIndex = availableFloors.indexOfFirst { it == 0 } // Znajdź index piętra 0
            if (defaultFloorIndex != -1) {
                spinnerFloor.setSelection(defaultFloorIndex) // Ustaw piętro 0 jako domyślne, jeśli istnieje
            } else if (availableFloors.isNotEmpty()) {
                spinnerFloor.setSelection(0) // Jeśli brak piętra 0, ustaw pierwszy element na liście
            }

        } ?: run {
            Log.e(TAG_FRAGMENT, "Błąd: Brak listy dostępnych pięter w argumentach Fragmentu.")
            Toast.makeText(requireContext(), "Błąd konfiguracji pięter.", Toast.LENGTH_LONG).show()
            // Jeśli brak danych o piętrach, wyłącz Spinner i przycisk dodawania punktów
            spinnerFloor.isEnabled = false
            buttonAddPoint.isEnabled = false
        }


        // --- Ustaw Listenery Przycisków ---
        setupAddPointButton() // Listener dla przycisku "Dodaj punkt"
        setupConfirmRouteButton() // Listener dla przycisku "Zatwierdź trasę"
        setupImportCsvButton() // Listener dla przycisku "Importuj trasę z CSV"

        // TODO: Opcjonalnie wyświetlaj listę dodanych punktów w UI Fragmentu (np. w TextView lub ListView)
        // To pomaga użytkownikowi śledzić dodane punkty i ich piętra.
    }

    // Ustawia listener dla przycisku "Dodaj punkt" (ręczne dodawanie)
    private fun setupAddPointButton() {
        buttonAddPoint.setOnClickListener {
            val xText = editTextCoordX.text.toString().trim()
            val yText = editTextCoordY.text.toString().trim()
            // Nazwa trasy jest potrzebna przy zatwierdzaniu, nie przy dodawaniu punktu

            // Walidacja pól X i Y
            if (xText.isBlank() || yText.isBlank()) {
                Toast.makeText(requireContext(), "Wprowadź obie współrzędne!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // --- POBIERZ WYBRANE PIĘTRO ZE SPINNERA ---
            // Sprawdź, czy Spinner ma wybrany element i lista dostępnych pięter nie jest pusta
            if (spinnerFloor.selectedItem == null || availableFloors.isEmpty()) {
                Toast.makeText(requireContext(), "Wybierz piętro dla punktu.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val selectedFloorString = spinnerFloor.selectedItem.toString()
            val selectedFloor: Int
            try {
                selectedFloor = selectedFloorString.toInt()
                Log.d(TAG_FRAGMENT, "Wybrano piętro ze Spinnera: $selectedFloor")
            } catch (e: NumberFormatException) {
                Log.e(TAG_FRAGMENT, "Błąd parsowania wybranego piętra: $selectedFloorString", e)
                Toast.makeText(requireContext(), "Błąd: Nieprawidłowa wartość piętra.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            try {
                val x = xText.toDouble()
                val y = yText.toDouble()

                // --- UTWÓRZ MapPoint Z INFORMACJĄ O PIĘTRZE ---
                val newPoint = MapPoint(x, y, selectedFloor) // Użyj X, Y i pobranego piętra

                // --- UTWÓRZ Marker Z NOWYM PUNKTEM (domyślnie PENDING) ---
                val newMarker = Marker(point = newPoint, state = MarkerState.PENDING) // Nowy punkt dodajemy jako PENDING

                // --- DODAJ NOWY MARKER DO TYMCZASOWEJ LISTY ---
                tempRouteMarkers.add(newMarker) // <-- DODAJ MARKER DO LISTY TYMCZASOWEJ
                Log.d(TAG_FRAGMENT, "Dodano punkt ręcznie do tymczasowej listy: (${x}, ${y}) na piętrze ${selectedFloor}. Liczba punktów: ${tempRouteMarkers.size}")

                // TODO: Opcjonalnie zaktualizuj UI Fragmentu, żeby pokazać dodane punkty

                // Wyczyść pola EditText dla X i Y po dodaniu punktu
                editTextCoordX.text.clear()
                editTextCoordY.text.clear()

                // Potwierdź dodanie punktu Toastem
                Toast.makeText(requireContext(), "Dodano punkt na piętrze ${selectedFloor}", Toast.LENGTH_SHORT).show()

            } catch (e: NumberFormatException) {
                Toast.makeText(requireContext(), "Wprowadź poprawne wartości liczbowe dla X i Y.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Ustawia listener dla przycisku "Zatwierdź trasę"
    private fun setupConfirmRouteButton() {
        buttonConfirmRoute.setOnClickListener {
            val routeName = editTextRouteName.text.toString().trim()

            // Walidacja: nazwa trasy nie może być pusta
            if (routeName.isBlank()) {
                Toast.makeText(requireContext(), "Podaj nazwę trasy!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Walidacja: musi być przynajmniej jeden punkt w tymczasowej liście
            if (tempRouteMarkers.isEmpty()) { // <-- SPRAWDŹ TYMCZASOWĄ LISTĘ MARKERÓW
                Toast.makeText(requireContext(), "Dodaj przynajmniej jeden punkt do trasy!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Wszystko wygląda OK, pokaż dialog zapytania o ostateczną nazwę i zapisz trasę.
            // Dialog poprosi o ostateczną nazwę i wywoła logikę zapisu.
            // Przekazujemy do dialogu listę markerów Z TYMCZASOWEJ LISTY, która ma być zapisana.
            showRouteNameDialog(tempRouteMarkers, routeName) // <-- PRZEKAŻ TYMCZASOWĄ LISTĘ MARKERÓW I DOMYŚLNĄ NAZWĘ
        }
    }

    // Ustawia listener dla przycisku "Importuj trasę z CSV"
    private fun setupImportCsvButton() {
        buttonImportCsv.setOnClickListener {
            openCsvFilePicker() // Użyj nazwy bez "Fragment" na końcu dla spójności
        }
    }

    // Otwiera systemowy selektor plików CSV
    private fun openCsvFilePicker() { // Zmieniono nazwę
        Log.d(TAG_FRAGMENT, "Otwieram systemowy selektor plików CSV.")
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "text/*" // Używamy "text/*" lub "*/*" bo typ MIME "text/csv" może być nierozpoznany.
            // type = "*/*" // Alternatywnie możesz pozwolić wybrać każdy plik

            addCategory(Intent.CATEGORY_OPENABLE) // Wymaga, żeby plik był otwieralny jako strumień
        }
        // Użyj registerForActivityResult lub startActivityForResult z opcją deprecacji
        startActivityForResult(intent, PICK_CSV_FILE_FRAGMENT) // Deprecated, ale działa na starszych API
        // TODO: Rozważ migrację do Activity Result API (registerForActivityResult)
    }

    // Obsługuje rezultat z systemowego selektora plików
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG_FRAGMENT, "onActivityResult called z requestCode: $requestCode, resultCode: $resultCode")

        // Sprawdź, czy rezultat pochodzi z naszego żądania selektora plików
        if (requestCode == PICK_CSV_FILE_FRAGMENT && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                Log.d(TAG_FRAGMENT, "Wybrano plik CSV z URI: $uri")
                readAndParseCsvFile(uri) // Wywołaj metodę do czytania i parsowania pliku CSV
            } ?: run {
                Log.w(TAG_FRAGMENT, "onActivityResult: URI danych jest nullem.")
                Toast.makeText(requireContext(), "Nie wybrano pliku.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.d(TAG_FRAGMENT, "onActivityResult: Nieobsługiwany requestCode ($requestCode) lub resultCode ($resultCode).")
        }
    }

    // Metoda do czytania i parsowania pliku CSV
    private fun readAndParseCsvFile(uri: Uri) {
        Log.d(TAG_FRAGMENT, "Rozpoczynam czytanie i parsowanie pliku CSV z URI: $uri.")

        val parsedMapPointsFromCsv = mutableListOf<MapPoint>() // Tymczasowa lista do przechowywania parsowanych MapPoint z CSV

        try {
            requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    // Pomijanie pierwszej linijki CSV (nagłówka)
                    val headerLine = reader.readLine()
                    if (headerLine != null) {
                        Log.d(TAG_FRAGMENT, "Pominięto pierwszą linię (nagłówek CSV): '$headerLine'")
                    } else {
                        Log.w(TAG_FRAGMENT, "Plik CSV jest pusty, nie było pierwszej linii do pominięcia.")
                    }


                    var line: String?
                    var lineNumber = 0 // Licznik linii danych (po nagłówku)

                    // Czytaj plik linijka po linijce (od drugiej fizycznej linii pliku)
                    while (reader.readLine().also { line = it } != null) {
                        lineNumber++ // Inkrementuj numer linii DANYCH
                        val currentLine = line?.trim() ?: "" // Pobierz bieżącą linijkę

                        // Pomiń puste linie lub linie komentarzy
                        if (currentLine.isEmpty() || currentLine.startsWith("#")) {
                            continue
                        }

                        // Podziel linijkę na części używając przecinka jako separatora
                        // ZMIENIONY FORMAT CSV: Oczekuj 3 części (X, Y, Piętro)
                        val parts = currentLine.split(",")

                        // Sprawdź, czy linijka ma dokładnie 3 części
                        if (parts.size == 3) { // <-- ZMIENIONO Z 2 NA 3
                            try {
                                // Spróbuj przekonwertować części na liczby (X, Y) i Int (Piętro)
                                val x = parts[0].trim().toDouble()
                                val y = parts[1].trim().toDouble()
                                val floor = parts[2].trim().toInt() // <-- POBIERZ PIĘTRO JAKO INT

                                // TODO: PAMIĘTAJ O MOŻLIWEJ ZAMIANIE KOLEJNOŚCI X i Y TUTAJ PRZY TWORZENIU MapPoint!
                                // Użyj poprawnej kolejności X i Y zgodnie z tym, jak Twoje dane mapowe są zdefiniowane.
                                val newPoint = MapPoint(x, y, floor) // <-- UTWÓRZ MapPoint Z POBRANYM PIĘTREM

                                parsedMapPointsFromCsv.add(newPoint) // <-- DODAJ DO TYMCZASOWEJ LISTY PARSOWANEJ CSV
                                Log.d(TAG_FRAGMENT, "Linia Danych $lineNumber: Parsowano punkt CSV: ($x, $y) na piętrze $floor")

                            } catch (e: NumberFormatException) {
                                // Błąd konwersji na Double (dla X/Y) lub Int (dla piętra)
                                Log.w(TAG_FRAGMENT, "Linia Danych $lineNumber: Błąd parsowania liczby lub piętra z CSV: ${e.message}. Linijka: '$currentLine'")
                                Toast.makeText(requireContext(), "CSV: Błąd w linii $lineNumber: '${currentLine}' - nieprawidłowa liczba/piętro.", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) { // Złap inne błędy parsowania
                                Log.w(TAG_FRAGMENT, "Linia Danych $lineNumber: Nieoczekiwany błąd parsowania CSV: ${e.message}. Linijka: '$currentLine'", e)
                                Toast.makeText(requireContext(), "CSV: Błąd w linii $lineNumber: '${currentLine}' - ${e.message}.", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            // Linijka nie ma 3 części
                            Log.w(TAG_FRAGMENT, "Linia Danych $lineNumber: Nieoczekiwana liczba części (${parts.size}) w CSV. Oczekiwano 3. Linijka: '$currentLine'")
                            Toast.makeText(requireContext(), "CSV: Błąd w linii $lineNumber: '${currentLine}' - nieprawidłowy format.", Toast.LENGTH_SHORT).show()
                        }
                    } // Koniec pętli while
                } // Koniec use reader
            } // Koniec use inputStream

            // --- ZAKOŃCZONO CZYTANIE PLIKU CSV ---

            // Sprawdź, czy wczytano jakiekolwiek punkty DANYCH z CSV po parsowaniu
            if (parsedMapPointsFromCsv.isEmpty()) { // <-- SPRAWDŹ TYMCZASOWĄ LISTĘ Z PARSOWANIA
                Log.w(TAG_FRAGMENT, "Brak poprawnie sparsowanych punktów danych z pliku CSV.")
                Toast.makeText(requireContext(), "Plik CSV jest pusty lub w całości zawiera błędy formatowania.", Toast.LENGTH_LONG).show()
                // Fragment pozostaje otwarty, użytkownik może spróbować ponownie lub wprowadzić ręcznie.
                return // Przerywamy proces, jeśli brak poprawnych punktów w CSV
            }

            Log.d(TAG_FRAGMENT, "Pomyślnie sparsowano ${parsedMapPointsFromCsv.size} poprawnych punktów z pliku CSV.")

            // --- Konwertuj sparsowane MapPointy z CSV na Markery i DODAJ JE DO GŁÓWNEJ LISTY TYMCZASOWEJ ---
            // Zakładamy, że punkty z CSV to również nowe punkty w tworzonej trasie, domyślnie PENDING.
            tempRouteMarkers.clear() // <-- CZYŚĆ GŁÓWNĄ LISTĘ TYMCZASOWĄ przed dodaniem punktów z CSV
            parsedMapPointsFromCsv.forEach { mapPoint ->
                tempRouteMarkers.add(Marker(point = mapPoint, state = MarkerState.PENDING)) // Utwórz Marker z MapPoint i dodaj do listy tymczasowej
            }
            Log.d(TAG_FRAGMENT, "Punkty z CSV (${parsedMapPointsFromCsv.size}) dodano do głównej listy tymczasowej. Łączna liczba punktów: ${tempRouteMarkers.size}")


            // TODO: Opcjonalnie zaktualizuj UI Fragmentu (np. wyświetl listę punktów wczytanych z CSV)

            // --- Zapytaj użytkownika o nazwę trasy ---
            // Dialog będzie używał punktów Z GŁÓWNEJ LISTY TYMCZASOWEJ (tempRouteMarkers), która teraz zawiera punkty z CSV.
            val defaultRouteName = getFileNameFromUri(uri) ?: "Trasa z CSV" // Pobierz nazwę pliku CSV lub użyj domyślnej
            val routeNameWithoutExtension = defaultRouteName.removeSuffix(".csv") // Usuń rozszerzenie .csv

            showRouteNameDialog(tempRouteMarkers, routeNameWithoutExtension) // <-- PRZEKAŻ GŁÓWNĄ LISTĘ TYMCZASOWĄ MARKERÓW

        } catch (e: IOException) {
            Log.e(TAG_FRAGMENT, "Błąd odczytu pliku CSV: ${e.message}", e)
            Toast.makeText(requireContext(), "Błąd odczytu pliku CSV: ${e.message}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG_FRAGMENT, "Nieoczekiwany błąd podczas przetwarzania CSV: ${e.message}", e)
            Toast.makeText(requireContext(), "Błąd podczas przetwarzania pliku CSV.", Toast.LENGTH_LONG).show()
        }
    }

    // Metoda pomocnicza do pobierania nazwy pliku z URI (używana przy imporcie CSV)
    private fun getFileNameFromUri(uri: Uri): String? {
        // ... (Twoja istniejąca implementacja getFileNameFromUri) ...
        Log.d(TAG_FRAGMENT, "Próbuję pobrać nazwę pliku z URI: $uri")
        val contentResolver: ContentResolver = requireContext().contentResolver
        val cursor = contentResolver.query(uri, null, null, null, null)

        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    val fileName = it.getString(nameIndex)
                    Log.d(TAG_FRAGMENT, "Pobrano nazwę pliku z URI: $fileName")
                    return fileName
                } else {
                    Log.w(TAG_FRAGMENT, "Nie znaleziono kolumny DISPLAY_NAME dla URI.")
                }
            } else {
                Log.w(TAG_FRAGMENT, "Kursor dla URI pusty.")
            }
        } ?: run {
            Log.w(TAG_FRAGMENT, "ContentResolver.query() zwrócił null dla URI.")
        }

        Log.w(TAG_FRAGMENT, "Nie udało się pobrać nazwy pliku z URI.")
        return null
    }


    // Metoda do wyświetlania dialogu zapytania o nazwę trasy przed zapisem
    // Teraz przyjmuje List<Marker> (punkty do zapisania)
    private fun showRouteNameDialog(markersToSave: List<Marker>, defaultName: String) { // <-- PRZYJMUJ LISTĘ MARKERÓW
        Log.d(TAG_FRAGMENT, "Pokazuję dialog zapytania o nazwę trasy. Liczba markerów do zapisania: ${markersToSave.size}")

        val inputEditText = EditText(requireContext()).apply {
            hint = "Wprowadź nazwę trasy"
            setText(defaultName) // Ustaw domyślną nazwę (z CSV lub z pola nazwy trasy)
            setSelection(defaultName.length) // Ustaw kursor na końcu tekstu
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Podaj nazwę trasy") // Bardziej ogólny tytuł dialogu
            .setView(inputEditText)
            .setPositiveButton("Zatwierdź") { dialog, which ->
                val routeName = inputEditText.text.toString().trim()

                // --- Logika po zatwierdzeniu nazwy w dialogu ---
                if (routeName.isBlank()) { // Użyj isBlank() żeby sprawdzić czy nazwa nie jest pusta lub zawiera tylko białe znaki
                    Toast.makeText(requireContext(), "Nazwa trasy nie może być pusta.", Toast.LENGTH_SHORT).show()
                    // Dialog zamknie się, ale użytkownik może kliknąć "Zatwierdź trasę" ponownie.
                    return@setPositiveButton // Przerywamy dalszą logikę, jeśli nazwa pusta
                }

                // --- Stwórz obiekt Route Z MARKERÓW DO ZAPISANIA ---
                // markersToSave to już jest List<Marker> (pochodzi z tempRouteMarkers lub z CSV)
                val newRoute = Route(name = routeName, markers = markersToSave) // <-- UŻYJ PRZEKAZANEJ LISTY MARKERÓW

                // --- Zapisz trasę do pamięci trwałej ---
                try {
                    // Upewnij się, że RouteStorage.addRoute jest dostępny i działa poprawnie
                    RouteStorage.addRoute(requireContext(), newRoute) // Zapisz nową trasę

                    Log.i(TAG_FRAGMENT, "Trasa '$routeName' zapisana pomyślnie (${newRoute.markers.size} punktów)!")
                    Toast.makeText(requireContext(), "Trasa '$routeName' zapisana!", Toast.LENGTH_LONG).show()

                    // --- Zakończ Fragment i wróć do Activity ---
                    // Wyślij rezultat z powrotem do Activity, żeby odświeżyło listę tras.
                    val resultBundle = Bundle().apply {
                        putBoolean("route_saved_success", true) // Ten sam klucz co Activity nasłuchuje
                    }
                    requireActivity().supportFragmentManager.setFragmentResult("route_saved_key", resultBundle)
                    Log.d(TAG_FRAGMENT, "Wysłano rezultat fragmentu po zapisie.")

                    // Wyczyść tymczasową listę po udanym zapisie
                    tempRouteMarkers.clear()

                    // Zamknij Fragment (powrót do poprzedniego Activity/Fragmentu na stosie wstecz)
                    // Używamy opóźnienia, żeby upewnić się, że rezultat dotrze do Activity, zanim Fragment zniknie.
                    Log.d(TAG_FRAGMENT, "Przygotowanie do popBackStack() po zapisie.")
                    Handler(Looper.getMainLooper()).postDelayed({
                        Log.d(TAG_FRAGMENT, "Wywołanie popBackStack() po opóźnieniu.")
                        requireActivity().supportFragmentManager.popBackStack()
                    }, 100) // Opóźnienie 100 ms


                } catch (e: Exception) {
                    Log.e(TAG_FRAGMENT, "Błąd podczas zapisu trasy '$routeName'.", e)
                    Toast.makeText(requireContext(), "Błąd zapisu trasy!", Toast.LENGTH_LONG).show()
                    // Fragment pozostaje otwarty w przypadku błędu zapisu.
                }

            } // Dialog zamyka się automatycznie po kliknięciu pozytywnego przycisku
            .setNegativeButton("Anuluj", null) // Przycisk Anuluj (domyślne zachowanie - zamknij dialog)
            .show() // Pokaż dialog
    }
}