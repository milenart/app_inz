package pl.pw.epicgameproject


import pl.pw.epicgameproject.MapConverter
import pl.pw.epicgameproject.WorldFileParameters
// Importy Android
import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.PointF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.graphics.BitmapFactory
import androidx.appcompat.widget.Toolbar
import android.view.Menu
import android.view.MenuItem
import android.widget.LinearLayout
import androidx.fragment.app.FragmentManager

fun Int.dpToPx(): Int {
    return (this * Resources.getSystem().displayMetrics.density).toInt()
}

class MainActivity : AppCompatActivity(), SensorEventListener, FragmentManager.OnBackStackChangedListener {

    // --- Deklaracje widoków z nowego layoutu ---
    private lateinit var floorPlanImageView: ImageView
    private lateinit var routeOverlayView: RouteOverlayView
    private lateinit var startButton: Button
    private lateinit var nextButton: Button
    private lateinit var stopButton: Button
    private lateinit var toolbar: Toolbar
    private lateinit var floorButtonsContainer: LinearLayout
    private val floorButtons = mutableMapOf<Int, Button>()



    private var mapConverter: MapConverter? = null

    // mapowanie sciezek do pięter
    private val floorPlanBitmapMap = mutableMapOf<Int, Bitmap>()
    private val floorFileMappingPng = mapOf(
        0 to "gmach_f0.png",
        1 to "gmach_f1.png",
        2 to "gmach_f2.png",
        3 to "gmach_f3.png",
        4 to "gmach_f4.png",
    )
    private var currentFloor: Int = 0

    // Ścieżki
    private var routes: List<Route> = emptyList()

    // Wymiary obrazka
    private var bitmapWidth: Int = 0
    private var bitmapHeight: Int = 0

    // --- Managery Systemowe ---
    private lateinit var wifiManager: WifiManager
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null

    // --- Stan Logowania ---
    private var isLogging = false

    // --- Przechowywanie Danych (w pamięci) ---
    private val wifiData = mutableListOf<Array<String>>()
    private val bleData = mutableListOf<Array<String>>()
    private val accelerometerData = mutableListOf<Array<String>>()
    private val gyroscopeData = mutableListOf<Array<String>>()
    private val magnetometerData = mutableListOf<Array<String>>()
    private val barometerData = mutableListOf<Array<String>>()
    private val allData = mutableListOf<Array<String>>()


    // --- Sensory ---
    private var accelerometerSensor: Sensor? = null
    private var gyroscopeSensor: Sensor? = null
    private var magnetometerSensor: Sensor? = null
    private var barometerSensor: Sensor? = null

    // Stan aplikacji
    enum class AppState {
        IDLE,                // Brak wybranej trasy, przycisk Start wyłączony, trasa niewidoczna
        ROUTE_SELECTED,      // Trasa wybrana (po dialogu), przycisk Start włączony, trasa PENDING/wybrana widoczna
        STARTED,             // Nawigacja rozpoczęta (po kliknięciu Start), przycisk Dalej widoczny
        READY_FOR_STOP,      // Przy przedostatnim punkcie (po kliknięciu Dalej), przycisk Stop widoczny
        FINISHED_DISPLAYED   // Nawigacja zakończona (po kliknięciu Stop), trasa ukończona widoczna, przycisk Start włączony
    }

    // zmienne potrzebne do obsługi przycisków
    private var currentAppState: AppState = AppState.IDLE // Aktualny stan aplikacji
    private var selectedRoute: Route? = null // Aktualnie wybrana trasa (przechowuje MapPoint)
    private var currentPointIndex: Int = 0

    // --- Ścieżka ---
    private var currentRelativeLogPath: String? = null

    // --- Handlery i Opóźnienia ---
    private val handler = Handler(Looper.getMainLooper())

    // --- Uprawnienia ---
    companion object {
        private const val REQUEST_PERMISSIONS_CODE = 1
        private val REQUIRED_PERMISSIONS: Array<String> by lazy {
            mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
            ).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // API 31+
                    add(Manifest.permission.BLUETOOTH_SCAN)
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // API 29+
                    add(Manifest.permission.ACTIVITY_RECOGNITION)
                }
            }.toTypedArray()
        }
        private const val TAG = "MultiSensorLog"
    }

    // --- Odbiornik Skanowania WiFi ---
    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!isLogging) return

            if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION == intent.action) {
                val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                if (success) {
                    Log.d("myWifiScan", "WiFi: Otrzymano nowe wyniki skanowania.")
                    processWifiScanResults()
                    Log.d("myWifiScan", "WiFi: Zainicjowano nowe skanowanie, czas: ${System.currentTimeMillis()}")
                    if (isLogging) {
                        triggerWifiScan()
                    }
                } else {
                    Log.d("myWifiScan", "WiFi: Otrzymano stare (cached) wyniki skanowania.")
                    Log.d("myWifiScan", "WiFi: Zainicjowano nowe skanowanie, czas: ${System.currentTimeMillis()}")
                    handler.postDelayed({
                        if (isLogging) {
                            Log.d("myWifiScan", "WiFi: Rozpoczęcie nowego skanowania o czasie: ${System.currentTimeMillis()}")
                            triggerWifiScan()
                        }
                    }, 500)
                }
            }
        }
    }

    // --- Callback Skanowania BLE ---
    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.let {
                if (isLogging) {
                    processBleScanResult(it)
                }
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
            results?.let {
                if (isLogging) {
                    Log.d(TAG, "BLE: Otrzymano ${it.size} wyników w batchu.")
                    it.forEach { result -> processBleScanResult(result) }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "BLE: Skanowanie nie powiodło się, kod błędu: $errorCode")
            if (isLogging) {
                handler.postDelayed({ startBleScan() }, 2000)
            }
        }
    }


    // --- Cykl Życia Aktywności ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.d(TAG, "onCreate called")

        // Rejestracja Listenera dla zmiany widoku
        supportFragmentManager.addOnBackStackChangedListener(this)

        // Inicjalizacja Managerów
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager =
            getSystemService(Context.SENSOR_SERVICE) as SensorManager // Inicjalizacja SensorManager

        // Inicjalizacja Bluetooth
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.w(TAG, "Bluetooth nie jest dostępny lub włączony.")
            // Można poprosić użytkownika o włączenie Bluetooth:
            // val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            // startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT) // Trzeba zdefiniować REQUEST_ENABLE_BT
        } else {
            bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        }

        // Znalezienie Sensorów
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magnetometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        barometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

        if (accelerometerSensor == null) Log.w(
            TAG,
            "Sensor przyspieszenia (ACCELEROMETER) nie znaleziony!"
        )
        if (gyroscopeSensor == null) Log.w(TAG, "Sensor żyroskopu (GYROSCOPE) nie znaleziony!")
        if (magnetometerSensor == null) Log.w(
            TAG,
            "Sensor pola magnetycznego (MAGNETIC_FIELD) nie znaleziony!"
        )
        if (barometerSensor == null) Log.w(TAG, "Sensor ciśnienia (PRESSURE) nie znaleziony!")

        // Rejestracja Odbiornika WiFi
        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, intentFilter)

        // Sprawdzenie i Prośba o Uprawnienia
        if (!hasRequiredPermissions()) {
            requestMissingPermissions()
        }

        // --- Znajdowanie widoków z NOWEGO layoutu ---
        floorPlanImageView = findViewById(R.id.floorPlanImageView)
        routeOverlayView = findViewById(R.id.routeOverlayView)
        toolbar = findViewById(R.id.toolbar)

        startButton = findViewById(R.id.startButton)
        nextButton = findViewById(R.id.nextButton)
        stopButton = findViewById(R.id.stopButton)
        floorButtonsContainer = findViewById(R.id.floorButtonsContainer)

        // setup toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setTitle("Pomiar Trasy")

        // Ustawienie Komponentów UI ekranu głównego (bez przycisków wybierania piętra)
        setupMainButtons()

        // --- Utwórz przyciski wyboru pięter ---
        createFloorButtons()

        // --- Ładowanie plików mapy i utworzenie MapConvertera ---
        val pgwParameters = loadWorldFileParameters("gmach.pgw")
        val bitmapDimensions = loadAllFloorPlanBitmaps()

        // Utworzenie instancji MapConverter - potrzebne do konwersji współrzędnych
        if (pgwParameters != null && bitmapDimensions != null) {
            val (bitmapWidth, bitmapHeight) = bitmapDimensions
            mapConverter = MapConverter(pgwParameters, bitmapWidth, bitmapHeight) // <-- TWORZYMY INSTANCJĘ Z POBRANYMI DANYMI
            Log.i(TAG, "MapConverter utworzony pomyślnie.")
        } else {
            Log.e(TAG, "FATALNY BŁĄD: Nie udało się utworzyć MapConverter (brak PGW lub bitmapy). Aplikacja może nie działać poprawnie.")
            Toast.makeText(this, "Błąd inicjalizacji mapy.", Toast.LENGTH_LONG).show()
        }

        // nasluchwianie rezultatow z CreateRouteFragment
        supportFragmentManager.setFragmentResultListener("route_saved_key", this) { requestKey, bundle ->
            // Ta lambda (funkcja anonimowa) zostanie wywołana, gdy Fragment wyśle rezultat z tym kluczem

            Log.d(TAG, "Fragment result received z kluczem: $requestKey")

            // Sprawdź, czy rezultat wskazuje na pomyślny zapis (zakładając, że Fragment wysyła boolean "route_saved_success")
            val success = bundle.getBoolean("route_saved_success", false) // Domyślnie false

            if (success) {
                Log.d(TAG, "Rezultat: Trasa zapisana pomyślnie. Przeładowuję trasy i resetuję UI.")

                // Przeładuj listę tras z pliku po zapisie
                routes = RouteStorage.loadRoutes(this)
                Log.d(TAG, "Trasy przeładowane po zapisie Fragmentu. Liczba tras: ${routes.size}")

                resetAppState() // <-- TO POWINNO PRZYWRÓCIĆ WIDOCZNOŚĆ PRZYCISKU START

            } else {
                Log.w(TAG, "Rezultat: Zapis trasy niepowodzenie lub brak flagi sukcesu.")
                resetAppState() // Wróć do stanu głównego UI nawet przy błędzie zapisu
            }
        }

        // Wczytaj trasy z pamięci trwałej
        routes = RouteStorage.loadRoutes(this)

        this.currentFloor = floorFileMappingPng.keys.firstOrNull() ?: 0 // Ustaw na pierwsze zdefiniowane piętro lub 0
        if (routes.isNotEmpty() && routes.first().markers.isNotEmpty()) {
            // Jeśli jest domyślna trasa z punktami, ustaw początkowe piętro na piętro pierwszego punktu tej trasy
            this.currentFloor = routes.first().markers.first().point.floor
            Log.d(TAG, "Ustawiono początkowe piętro na podstawie pierwszej trasy: ${this.currentFloor}")
        } else {
            Log.d(TAG, "Brak tras lub pierwsza trasa jest pusta. Ustawiono początkowe piętro na domyślne: ${this.currentFloor}")
        }


        // Ustawienie początkowego stanu UI po wczytaniu tras i ustawieniu initial currentFloor
        if (routes.isNotEmpty()) {
            this.selectedRoute = routes.first() // Wybierz pierwszą trasę jako domyślną
            this.currentPointIndex = 0 // Ustaw index początkowy dla wyświetlania (np. -1, bo nawigacja jeszcze nieaktywna)

            // Wyświetl trasę dla początkowego piętra (displayGeographicRoute UŻYJE mapConvertera)
            if (mapConverter != null) { // Upewnij się, że mapConverter jest dostępny
                displayGeographicRoute(this.selectedRoute, this.currentPointIndex) // <-- WYWOŁANIE displayGeographicRoute
            } else {
                Log.e(TAG, "displayGeographicRoute: MapConverter jest nullem w onCreate po ładowaniu, nie mogę wyświetlić trasy.")
            }

            currentAppState = AppState.ROUTE_SELECTED // Ustaw stan aplikacji
            supportActionBar?.setTitle(selectedRoute?.name) // Ustaw tytuł toolbar

        } else {
            // Stan, gdy brak wczytanych tras
            this.selectedRoute = null
            this.currentPointIndex = 0
            routeOverlayView.clearRoute() // Wyczyść overlay
            // Ustaw bitmapę dla domyślnego piętra nawet jeśli brak trasy
            floorPlanBitmapMap[this.currentFloor]?.let {
                floorPlanImageView.setImageBitmap(it)
                Log.d(TAG, "Ustawiono bitmapę domyślnego piętra ($currentFloor) gdy brak tras.")
            } ?: floorPlanImageView.setImageDrawable(null) // Ustaw bitmapę lub wyczyść jeśli brak

            currentAppState = AppState.IDLE // Ustaw stan aplikacji
            supportActionBar?.setTitle("Wybierz Trasę") // Ustaw ogólny tytuł
        }

        // --- Zaktualizuj stan wizualny przycisków pięter ---
        updateFloorButtonState() // <-- WYWOŁANIE aktualizacji wyglądu przycisków pięter

    }

    private fun updateRouteState(route: Route, currentTargetIndex: Int) {
        Log.d(TAG, "updateRouteState wywołane dla trasy '${route.name}' z target index $currentTargetIndex")

        val totalMarkers = route.markers.size // Całkowita liczba punktów w trasie

        // Sprawdzamy, czy index celu jest w ogóle sensowny
        if (currentTargetIndex < 0 || currentTargetIndex >= totalMarkers) {
            Log.w(TAG, "updateRouteState: currentTargetIndex ($currentTargetIndex) poza zakresem trasy (${totalMarkers} punktów). Ustawiam wszystkie na PENDING (chyba że są końcowe).")
            // Jeśli index celu jest nieprawidłowy, wszystkie punkty po prostu powinny być PENDING
            // chyba że są ostatnim punktem trasy.
        }


        // Iteruj przez WSZYSTKIE markery w pełnej liście trasy
        route.markers.forEachIndexed { index, marker ->
            val newState: MarkerState

            // --- Ustawienie podstawowego stanu (VISITED, CURRENT, PENDING) ---
            if (index < currentTargetIndex) {
                // Punkty przed aktualnym celem są ODWIEDZONE (jeśli index celu jest sensowny)
                newState = if (currentTargetIndex >= 0 && currentTargetIndex <= totalMarkers) {
                    MarkerState.VISITED
                } else {
                    MarkerState.PENDING // Jeśli index celu poza zakresem, nic nie jest odwiedzone przez logikę nawigacji
                }

            } else if (index == currentTargetIndex && currentTargetIndex < totalMarkers) {
                // Punkt o aktualnym indexie celu jest AKTUALNYM CELEM
                newState = MarkerState.CURRENT

            } else { // index > currentTargetIndex
                // Punkty po aktualnym celu są OCZEKUJĄCE
                newState = MarkerState.PENDING
            }

            // --- Nadpisanie stanu dla OSTATNIEGO PUNKTU CAŁEJ TRASY ---
            // Ten punkt zawsze ma stan END, niezależnie od tego, czy został już "odwiedzony"
            // w sensie nawigacji (może być VISITED + END, CURRENT + END, PENDING + END).
            // Stan END ma najwyższy priorytet wizualny (czarny kolor).
            if (index == totalMarkers - 1) { // Sprawdź, czy to ostatni punkt na liście
                // Jeśli to ostatni punkt, ustaw jego stan na END
                marker.state = MarkerState.END // Nadpisz podstawowy stan na END
                Log.d(TAG, "updateRouteState: Marker ${index} ustawiono na END (ostatni punkt trasy).")
            } else {
                // Dla wszystkich innych punktów użyj podstawowego stanu
                marker.state = newState
            }

            // TODO: Stan LAST_ON_FLOOR (fioletowy) będzie obsługiwany WIZUALNIE w onDraw RouteOverlayView,
            // a nie jako stan w MarkerState. Musimy to wykryć w onDraw.
        }

        Log.d(TAG, "updateRouteState: Zakończono aktualizację stanów markerów.")
    }

    private fun createFloorButtons() {
        Log.d(TAG, "Tworzę przyciski wyboru pięter.")
        floorButtonsContainer.removeAllViews() // Wyczyść kontener na wypadek ponownego wywołania
        floorButtons.clear()

        // Pobierz i posortuj numery pięter zdefiniowane w mapie floorFileMappingPng
        val sortedFloors = floorFileMappingPng.keys.sorted()

        // Sprawdź, czy są zdefiniowane piętra
        if (sortedFloors.isEmpty()) {
            Log.w(TAG, "Brak zdefiniowanych pięter w floorFileMappingPng. Nie mogę utworzyć przycisków.")
            return
        }

        for (floor in sortedFloors) {
            // Utwórz nowy przycisk
            val button = Button(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, // Szerokość: 0dp
                    LinearLayout.LayoutParams.WRAP_CONTENT, // Wysokość: wrap_content
                    1f // Waga: 1, żeby przyciski rozciągnęły się na całą szerokość kontenera
                ).apply {
                    // Dodaj mały margines między przyciskami, pomijając ostatni
                    if (floor != sortedFloors.last()) {
                        marginEnd = 4.dpToPx()
                    }
                }
                text = floor.toString() // Tekst przycisku to numer piętra
                isAllCaps = false // Niech tekst będzie taki jak wpisano
                // TODO: Możesz dodać Style do przycisków, żeby wyglądały lepiej i reagowały na isSelected

                // Ustaw listener kliknięcia dla każdego przycisku
                setOnClickListener {
                    // --- Listener kliknięcia przycisku piętra ---
                    Log.d(TAG, "Kliknięto przycisk piętra: $floor")
                    // Sprawdź, czy kliknięto przycisk innego piętra niż aktualne
                    if (this@MainActivity.currentFloor != floor) {
                        this@MainActivity.currentFloor = floor // Zaktualizuj aktualne piętro
                        updateFloorButtonState() // Zaktualizuj wizualny stan przycisków (który jest "wciśnięty")
                        // Wyświetl trasę dla nowego piętra (jeśli jest wybrana trasa)
                        if (this@MainActivity.selectedRoute != null) {
                            // Wywołaj displayGeographicRoute - ta metoda teraz sama użyje currentFloor
                            displayGeographicRoute(this@MainActivity.selectedRoute, this@MainActivity.currentPointIndex)
                        } else {
                            // Jeśli brak wybranej trasy, po prostu zmień wyświetlany plan piętra
                            routeOverlayView.clearRoute() // Wyczyść overlay
                            // Ustaw bitmapę dla nowego piętra
                            floorPlanBitmapMap[this@MainActivity.currentFloor]?.let {
                                floorPlanImageView.setImageBitmap(it)
                            } ?: floorPlanImageView.setImageDrawable(null) // Ustaw bitmapę lub wyczyść jeśli brak
                            routeOverlayView.invalidate() // Wymuś przerysowanie overlay (pustego)
                        }
                        // TODO: Ewentualnie zaktualizuj UI stanu aplikacji (np. tytuł toolbar, komunikaty)
                    }
                }
            }
            // Dodaj utworzony przycisk do kontenera LinearLayout
            floorButtonsContainer.addView(button)
            // Zapisz przycisk w mapie, używając numeru piętra jako klucza
            floorButtons[floor] = button
        }
        Log.d(TAG, "Zakończono tworzenie przycisków pięter. Utworzono ${floorButtons.size} przycisków.")
    }

    private fun updateFloorButtonState() {
        Log.d(TAG, "Aktualizuję stan przycisków pięter. Aktualne piętro: $currentFloor")
        // Iteruj przez wszystkie przyciski pięter przechowywane w mapie
        for ((floor, button) in floorButtons) {
            if (floor == currentFloor) {
                // To jest przycisk aktualnego piętra - ustawiamy go na "wciśnięty" (selected = true)
                button.isSelected = true // Stan 'selected' można wykorzystać w selektorach tła/koloru tekstu w styles.xml
                // Możesz też bezpośrednio zmienić wygląd:
                button.setBackgroundColor(ContextCompat.getColor(this, R.color.colorPrimary)) // Zmień kolor tła na główny kolor aplikacji
                button.setTextColor(ContextCompat.getColor(this, android.R.color.white)) // Zmień kolor tekstu na biały
            } else {
                // To nie jest przycisk aktualnego piętra - ustawiamy go na "normalny" (selected = false)
                button.isSelected = false
                // Przywróć normalny wygląd:
                // Użyj kolorów, które pasują do Twojego motywu/stylu przycisków Material Design
                button.setBackgroundColor(ContextCompat.getColor(this, R.color.my_default_button_color)) // Użyj domyślnego koloru tła przycisku Material
                button.setTextColor(ContextCompat.getColor(this, android.R.color.black)) // Użyj domyślnego koloru tekstu przycisku (lub koloru ze stylu)
            }
        }
        // Po zmianie wyglądu przycisków, wymuś przerysowanie kontenera (może nie być konieczne, ale dla pewności)
        floorButtonsContainer.invalidate()
        floorButtonsContainer.requestLayout() // Wymuś ponowne obliczenie layoutu
    }

    fun displayGeographicRoute(routeToDisplay: Route?, currentTargetIndex: Int = 0) { // Zmieniono domyślną wartość na 0
        Log.d(TAG, "displayGeographicRoute wywołane. Trasa: ${routeToDisplay?.name}, currentTargetIndex (cały route): ${this.currentPointIndex}, Rysuję dla PIĘTRA: $currentFloor") // Logujemy this.currentPointIndex


        // --- Ustaw odpowiednią bitmapę planu piętra w ImageView ---
        floorPlanBitmapMap[currentFloor]?.let {
            floorPlanImageView.setImageBitmap(it)
            Log.d(TAG, "Ustawiono bitmapę dla pięcia: $currentFloor")
        } ?: run {
            floorPlanImageView.setImageDrawable(null)
            Log.w(TAG, "Brak bitmapy planu piętra dla piętra: $currentFloor. ImageView wyczyszczone.")
            routeOverlayView.clearRoute() // Upewnij się, że overlay jest też czyszczony
            routeOverlayView.invalidate()
            return // Przerwij, bo brak mapy do rysowania
        }


        routeOverlayView.clearRoute() // Wyczyść poprzednie rysunki z overlay


        // Sprawdź, czy trasa istnieje i ma punkty
        if (routeToDisplay == null || routeToDisplay.markers.isEmpty()) {
            Log.w(TAG, "displayGeographicRoute: Brak trasy do wyświetlenia lub trasa jest pusta.")
            // Tutaj też musimy przekazać pełne 5 argumentów, nawet jeśli lista markerów jest pusta
            // Poprawione wywołanie setRouteAndStates dla pustej trasy/pustego piętra
            routeOverlayView.setRouteAndStates(emptyList(), -1, mapConverter!!, -1, 0, null) // 0 jako totalRouteMarkerCount gdy trasa pusta
            routeOverlayView.invalidate()
            return
        }

        // --- WAŻNE: Zaktualizuj stany WSZYSTKICH markerów trasy przed filtrowaniem ---
        // Ta metoda modyfikuje stany markerów BEZPOŚREDNIO w obiekcie routeToDisplay.
        updateRouteState(routeToDisplay, this.currentPointIndex) // Użyj aktualnego currentPointIndex z MainActivity


        // --- Find the last marker of the PREVIOUS floor (if applicable) ---
        var previousFloorLastMarker: Marker? = null // Zmienna do przechowywania markera
        // Sprawdź, czy w ogóle ma sens szukać poprzedniego piętra (czy bieżące piętro nie jest pierwszym piętrem trasy)
        val firstMarkerOfEntireRoute = routeToDisplay.markers.first() // Pierwszy punkt całej trasy
        if (currentFloor != firstMarkerOfEntireRoute.point.floor) {
            // Jesteśmy na piętrze innym niż pierwsze piętro trasy, więc może istnieć poprzednie piętro z punktami.

            // Znajdź index PIERWSZEGO markera NA OBECNYM PIĘTRZE w PEŁNEJ liście trasy.
            // Użyjemy tego indexu do znalezienia markera przed nim.
            val indexOfFirstMarkerOfCurrentFloorInFullRoute = routeToDisplay.markers.indexOfFirst { it.point.floor == currentFloor }

            // Sprawdź, czy znaleziono pierwszy marker na obecnym piętrze I czy nie jest to pierwszy marker CAŁEJ trasy (czyli index > 0).
            if (indexOfFirstMarkerOfCurrentFloorInFullRoute > 0) {
                // Marker poprzedniego piętra jest bezpośrednio przed pierwszym punktem obecnego piętra
                // w PEŁNEJ liście markerów trasy.
                previousFloorLastMarker = routeToDisplay.markers[indexOfFirstMarkerOfCurrentFloorInFullRoute - 1]
                Log.d(TAG, "displayGeographicRoute: Znaleziono ostatni marker poprzedniego piętra (${previousFloorLastMarker?.point?.floor}): ${previousFloorLastMarker?.point}")

                // Stan tego markera został już zaktualizowany przez updateRouteState (powinien być VISITED lub END)
                // W Overlayu i tak zawsze rysujemy go jako VISITED (zielony) (zgodnie z Etapem 11.1).
            } else {
                // To by się zdarzyło, gdyby pierwszy marker na obecnym piętrze był jednocześnie pierwszym markerem CAŁEJ trasy,
                // ale weszliśmy do tego bloku, bo currentFloor != firstMarkerOfEntireRoute.point.floor.
                // To nie powinno się zdarzyć przy prawidłowej logice, ale logujemy jako zabezpieczenie.
                Log.w(TAG, "displayGeographicRoute: Wykryto nieoczekiwany scenariusz - currentFloor != pierwszemu piętru trasy, ale pierwszy marker na currentFloor ma index 0 w całej trasie.")
                previousFloorLastMarker = null // Upewnij się, że jest null
            }
        } else {
            // Jesteśmy na pierwszym piętrze trasy, więc nie ma poprzedniego piętra z punktami do wyświetlenia.
            Log.d(TAG, "displayGeographicRoute: Jesteśmy na pierwszym piętrze trasy (${currentFloor}). Brak ostatniego punktu poprzedniego piętra do wyświetlenia.")
            previousFloorLastMarker = null // Upewnij się, że jest null
        }


        // --- PRZETWARZAJ I ZBIERAJ TYLKO MARKERY Z AKTUALNEGO PIĘTRA ---
        val markersOnCurrentFloor = routeToDisplay.markers.filter { it.point.floor == currentFloor }
        Log.d(TAG, "displayGeographicRoute: Znaleziono ${markersOnCurrentFloor.size} punktów trasy na aktualnym piętrze ($currentFloor).")


        // Jeśli na bieżącym piętrze nie ma markerów trasy (poza potencjalnym punktem z poprzedniego piętra)
        if (markersOnCurrentFloor.isEmpty()) {
            Log.w(TAG, "displayGeographicRoute: Brak punktów trasy na aktualnym piętrze ($currentFloor).")
            // W tym przypadku przekazujemy pustą listę markerów na piętrze, ale PRZEKAZUJEMY TEŻ previousFloorLastMarker
            if (mapConverter != null) {
                // Poprawione wywołanie setRouteAndStates dla pustego piętra (z poprzednim punktem lub bez)
                routeOverlayView.setRouteAndStates(
                    emptyList(), // markersOnCurrentFloor (pusta)
                    -1,          // currentTargetIndexOnCurrentFloor (brak celu)
                    mapConverter!!, // mapConverter
                    -1,          // lastMarkerIndexOnCurrentFloor (brak ostatniego)
                    routeToDisplay.markers.size, // totalRouteMarkerCount (całkowita liczba z PEŁNEJ trasy)
                    previousFloorLastMarker // <-- PRZEKAŻ ZNALEZIONY PUNKT POPRZEDNIEGO PIĘTRA (lub null)
                )
                Log.d(TAG, "displayGeographicRoute: Przekazano dane (puste piętro) do RouteOverlayView.")
            } else {
                Log.e(TAG, "displayGeographicRoute: MapConverter jest nullem, nie mogę ustawić danych w Overlay (puste piętro).")
                routeOverlayView.clearRoute() // Upewnij się, że overlay jest czyszczony
            }
            routeOverlayView.invalidate()
            return // Zakończ wykonywanie funkcji, bo na tym piętrze nie ma markerów trasy (poza poprzednim)
        }


        // --- Kod wykonuje się TYLKO GDY markersOnCurrentFloor NIE JEST PUSTE ---


        // --- Znajdź odpowiadający index currentTargetIndex W RAMACH markersOnCurrentFloor ---
        // currentPointIndex to index w PEŁNEJ liście trasy. Musimy znaleźć ten marker,
        // a następnie jego index w przefiltrowanej liście markersOnCurrentFloor.
        val currentTargetMarkerInFullRoute = if (this.currentPointIndex != -1 && this.currentPointIndex < routeToDisplay.markers.size) {
            routeToDisplay.markers[this.currentPointIndex] // Pobierz marker celu z PEŁNEJ listy
        } else null // Będzie null, jeśli currentPointIndex poza zakresem lub -1

        // Znajdź index tego markera w przefiltrowanej liście (markersOnCurrentFloor)
        // Używamy indexOfFirst { } żeby znaleźć po punkcie, na wypadek duplikatów markerów z tym samym punktem na różnych piętrach (choć to rzadkie)
        val currentTargetIndexOnCurrentFloor = if (currentTargetMarkerInFullRoute != null && currentTargetMarkerInFullRoute.point.floor == currentFloor) {
            markersOnCurrentFloor.indexOfFirst { it.point == currentTargetMarkerInFullRoute.point }
            // Jeśli marker celu istnieje w pełnej trasie, jest na bieżącym piętrze, znajdź jego index w przefiltrowanej liście
        } else -1 // Jeśli marker celu nie istnieje, nie jest na bieżącym piętrze, lub currentPointIndex był -1/poza zakresem


        // --- Znajdź index OSTATNIEGO MARKERA NA AKTUALNYM PIĘTRZE (w ramach markersOnCurrentFloor) ---
        // Musimy znaleźć ostatni punkt w PEŁNEJ trasie, który jest na currentFloor.
        // Potem znaleźć jego index w przefiltrowanej liście markersOnCurrentFloor.
        val lastMarkerOnCurrentFloorInFullRoute = routeToDisplay.markers
            .filter { it.point.floor == currentFloor } // Filtruj wszystkie markery trasy na obecne piętro
            .lastOrNull() // Weź ostatni z nich

        // Znajdź index tego ostatniego markera na piętrze w przefiltrowanej liście (markersOnCurrentFloor)
        val lastMarkerIndexOnCurrentFloor = if (lastMarkerOnCurrentFloorInFullRoute != null) {
            markersOnCurrentFloor.indexOfFirst { it.point == lastMarkerOnCurrentFloorInFullRoute.point } // Znajdź index w przefiltrowanej liście
        } else -1 // Jeśli nie ma markerów na tym piętrze, lastMarkerOnCurrentFloorInFullRoute będzie null


        // --- Rysowanie na RouteOverlayView ---
        // Przekazujemy do RouteOverlayView:
        // 1. Przefiltrowaną listę markerów (tylko z aktualnego piętra).
        // 2. Index aktualnego celu W RAMACH TEJ PRZEFILTROWANEJ LISTY.
        // 3. Instancję MapConvertera.
        // 4. Index ostatniego markera NA TYM PIĘTRZE W RAMACH TEJ PRZEFILTROWANEJ LISTY.
        // 5. Całkowitą liczbę markerów w pełnej trasie (może się przydać dla stanu END, jeśli go nie ma w MarkerState).
        // 6. Ostatni punkt poprzedniego piętra (lub null).

        if (mapConverter != null) { // Upewnij się, że mapConverter jest dostępny
            // Poprawione główne wywołanie setRouteAndStates - dodajemy previousFloorLastMarker
            routeOverlayView.setRouteAndStates(
                markersOnCurrentFloor, // Przefiltrowana lista (niepusta w tym bloku)
                currentTargetIndexOnCurrentFloor, // Index celu W przefiltrowanej liście
                mapConverter!!, // Konwerter
                lastMarkerIndexOnCurrentFloor, // Index ostatniego na piętrze W przefiltrowanej liście
                routeToDisplay.markers.size, // Całkowita liczba punktów w trasie
                previousFloorLastMarker // <-- PRZEKAŻ ZNALEZIONY PUNKT POPRZEDNIEGO PIĘTRA (lub null)
            )
            Log.d(TAG, "displayGeographicRoute: Przekazano dane (z markerami na piętrze) do RouteOverlayView.")

        } else {
            Log.e(TAG, "displayGeographicRoute: MapConverter jest nullem, nie mogę ustawić danych w Overlay (z markerami na piętrze).")
            routeOverlayView.clearRoute() // Upewnij się, że overlay jest czyszczony
        }


        // Wymuś przerysowanie Overlay
        routeOverlayView.invalidate()

        Log.d(TAG, "displayGeographicRoute: Zakończono konfigurację rysowania dla piętra: $currentFloor.")
    }

    private fun loadWorldFileParameters(fileName: String): WorldFileParameters? {
        Log.d(TAG, "Attempting to load world file from assets: $fileName")
        val assetManager = assets

        try {
            val inputStream = assetManager.open(fileName)
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                val lines = reader.readLines()

                if (lines.size == 6) {
                    try {
                        val a = lines[0].trim().toDouble()
                        val b = lines[2].trim().toDouble()
                        val c = lines[4].trim().toDouble()
                        val d = lines[1].trim().toDouble()
                        val e = lines[3].trim().toDouble()
                        val f = lines[5].trim().toDouble()

                        Log.i(TAG, "World file '$fileName' loaded successfully from assets.")
                        Log.d(TAG, "Params: A=$a, B=$b, C=$c, D=$d, E=$e, F=$f")
                        // ZWRÓĆ OBIEKT WorldFileParameters
                        return WorldFileParameters(a, b, c, d, e, f) // <-- ZWRACAMY OBIEKT

                    } catch (e: NumberFormatException) {
                        Log.e(TAG, "Error parsing double from world file '$fileName': ${e.message}", e)
                        return null
                    }
                } else {
                    Log.e(TAG, "World file '$fileName' has ${lines.size} lines, expected 6.")
                    return null
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error reading world file from assets: $fileName", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error loading world file from assets: $fileName", e)
            return null
        }
    }

    private fun loadAllFloorPlanBitmaps(): Pair<Int, Int>? {
        Log.d(TAG, "Rozpoczynam ładowanie bitmap planów pięter dla wszystkich pięter.")
        floorPlanBitmapMap.clear() // Wyczyść poprzednią mapę bitmap

        var bitmapWidth = 0
        var bitmapHeight = 0
        var firstBitmapLoaded = false

        // Iteruj przez zdefiniowane mapowania piętro -> plik PNG
        for ((floor, pngFileName) in floorFileMappingPng) {
            val bitmap = loadBitmapFromAssets(pngFileName) // Użyj funkcji ładowania pojedynczej bitmapy
            if (bitmap != null) {
                floorPlanBitmapMap[floor] = bitmap // Zapisz bitmapę w mapie
                // Pobierz wymiary z pierwszej załadowanej bitmapy (bo wszystkie mają być takie same)
                if (!firstBitmapLoaded) {
                    bitmapWidth = bitmap.width
                    bitmapHeight = bitmap.height
                    firstBitmapLoaded = true
                    Log.d(TAG, "Pobrane wymiary bitmapy: ${bitmapWidth}x${bitmapHeight}")
                }
            } else {
                Log.e(TAG, "Bitmap for floor $floor ($pngFileName) could not be loaded.")
            }
        }

        Log.d(TAG, "Zakończono ładowanie bitmap pięter. Załadowano ${floorPlanBitmapMap.size} bitmap.")

        // Zwróć wymiary bitmapy jeśli załadowano co najmniej jedną, inaczej null
        return if (firstBitmapLoaded) Pair(bitmapWidth, bitmapHeight) else null
    }

    private fun loadBitmapFromAssets(fileName: String): Bitmap? {
        Log.d(TAG, "Attempting to load bitmap from assets: $fileName")
        val assetManager = assets // Pobierz AssetManager z kontekstu Activity

        try {
            // Otwórz InputStream do pliku w assets
            val inputStream = assetManager.open(fileName)
            // Dekoduj InputStream na obiekt Bitmap
            val bitmap = BitmapFactory.decodeStream(inputStream)
            // Zamknij strumień
            inputStream.close()

            if (bitmap != null) {
                Log.i(TAG, "Bitmap '$fileName' loaded successfully from assets. Dimensions: ${bitmap.width} x ${bitmap.height}")
            } else {
                Log.e(TAG, "Failed to decode bitmap from assets: $fileName. Returned null.")
            }
            return bitmap // Zwróć załadowaną Bitmapę lub null

        } catch (e: IOException) {
            // Obsługa błędów podczas otwierania lub czytania pliku
            Log.e(TAG, "Error loading bitmap from assets: $fileName", e)
            return null // Zwróć null na błędzie odczytu pliku
        } catch (e: Exception) {
            // Złap inne nieoczekiwane wyjątki
            Log.e(TAG, "Unexpected error loading bitmap from assets: $fileName", e)
            return null
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "=== onResume called ===") // Log na początku onResume

        val backStackCount = supportFragmentManager.backStackEntryCount
        Log.d(TAG, "onResume: supportFragmentManager.backStackEntryCount = $backStackCount")

        // Sprawdź, czy wróciliśmy do głównego layoutu Activity
        if (backStackCount == 0) {
            Log.d(TAG, "onResume: Stos wstecz jest pusty (count = 0). Przygotowuję się do resetu UI.")
            // Wywołaj metodę resetującą stan UI
            resetAppState() // <-- WYWOŁANIE resetAppState()
            Log.d(TAG, "onResume: resetAppState() wywołane.")

        } else {
            Log.d(TAG, "onResume: Stos wstecz ma $backStackCount elementów. Fragment jest prawdopodobnie nadal widoczny.")
            // Upewnij się, że przyciski nawigacyjne są ukryte, jeśli Fragment jest widoczny
            startButton.visibility = View.GONE
            nextButton.visibility = View.GONE
            stopButton.visibility = View.GONE
            Log.d(TAG, "onResume: Przyciski nawigacyjne ustawione na GONE.")
        }
        Log.d(TAG, "=== Koniec onResume ===") // Log na końcu onResume

    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause called")
        // Zatrzymanie logowania i sensorów jest kluczowe w onPause, aby uniknąć wycieków i pracy w tle.
        if (isLogging) {
            Log.d(TAG, "Pauzowanie aktywności, zatrzymywanie logowania.")
            // Nie zapisujemy danych w onPause, tylko zatrzymujemy zbieranie.
            // Użytkownik musi kliknąć STOP, aby zapisać.
            // Ale musimy zatrzymać skanery i sensory.
            stopBleScan()
            sensorManager.unregisterListener(this) // Wyrejestrowujemy sensory
            // Pozostawiamy isLogging = true, aby wiedzieć, że proces był aktywny.
            // Ale zatrzymujemy aktywne skanowanie.
            handler.removeCallbacksAndMessages(null) // Usuwamy oczekujące wywołania skanowania WiFi
        }
    }

    override fun onBackStackChanged() {
        Log.d(TAG, "onBackStackChanged called. Current back stack count: ${supportFragmentManager.backStackEntryCount}")

        // Sprawdź, czy stos wstecz jest pusty.
        // Gdy count == 0, oznacza to, że wróciliśmy do głównego layoutu Activity.
        if (supportFragmentManager.backStackEntryCount == 0) {
            Log.d(TAG, "onBackStackChanged: Stos wstecz jest pusty. Resetuję stan UI.")
            // Wywołaj metodę resetującą stan UI
            resetAppState() // <-- WYWOŁAJ resetAppState() KIEDY STOS PUSTY
            Log.d(TAG, "onBackStackChanged: resetAppState() wywołane.")
        }
        // Jeśli count > 0, oznacza to, że jakiś fragment jest na stosie (lub właśnie został dodany/usunięty,
        // ale nadal jest inny fragment na wierzchu), więc przyciski nawigacyjne powinny być ukryte.
        else {
            // Możesz dodać logowanie, jeśli chcesz potwierdzić, że ten blok też jest wywoływany
            Log.d(TAG, "onBackStackChanged: Stos wstecz nie jest pusty. Pozostaję w stanie Fragmentu.")
            // Upewnij się, że przyciski są ukryte, jeśli stos nie jest pusty
            startButton.visibility = View.GONE
            nextButton.visibility = View.GONE
            stopButton.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called")
        // Upewnij się, że odbiornik jest wyrejestrowany
        try {
            unregisterReceiver(wifiScanReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Odbiornik WiFi już wyrejestrowany lub nigdy nie zarejestrowany.", e)
        }
        // Upewnij się, że logowanie jest całkowicie zatrzymane i zasoby zwolnione
        if (isLogging) {
            stopLoggingInternal(saveData = false) // Zatrzymujemy bez zapisu, jeśli onDestroy jest wywołane niespodziewanie
        }
        handler.removeCallbacksAndMessages(null) // Dodatkowe zabezpieczenie
        supportFragmentManager.removeOnBackStackChangedListener(this)
    }

    private fun setupMainButtons() {
        Log.d(TAG, "Setting up buttons")

        startButton.visibility = View.VISIBLE
        startButton.isEnabled = false
        nextButton.visibility = View.GONE
        stopButton.visibility = View.GONE


        startButton.setOnClickListener {
            Log.d(TAG, "Przycisk START kliknięty.")

            // Sprawdź, czy jesteśmy w stanie, w którym można rozpocząć nawigację (np. ROUTE_SELECTED)
            // i czy trasa jest wybrana i niepusta.
            // currentPointIndex == 0 w stanie ROUTE_SELECTED oznacza gotowość do startu.
            if (currentAppState != AppState.ROUTE_SELECTED || this.selectedRoute == null || this.selectedRoute!!.markers.isEmpty() || this.currentPointIndex != 0) {
                Log.w(TAG, "Przycisk Start kliknięty w nieoczekiwanym stanie (${currentAppState}) lub brak trasy/index niezerowy (${this.currentPointIndex}). Resetuję stan.")
                resetAppState() // W przypadku nieoczekiwanego stanu, resetujemy
                return@setOnClickListener
            }

            val route = this.selectedRoute!!
            val totalMarkers = route.markers.size

            // --- LOGIKA PO KLIKNIĘCIU "START" ---
            // Nawigacja się rozpoczyna. Punkt o indexie 0 (pierwszy) jest celem.
            // Po kliknięciu Start, punkt 0 jest "osiągnięty", staje się odwiedzony, a punkt 1 staje się nowym celem.

            // Sprawdź, czy trasa ma przynajmniej jeden punkt.
            if (totalMarkers == 0) {
                Log.w(TAG, "Przycisk START kliknięty, ale wybrana trasa jest pusta mimo checku.")
                resetAppState()
                return@setOnClickListener
            }

            currentAppState = AppState.STARTED
            Log.d(TAG, "Stan aplikacji zmieniony na: ${currentAppState}. Rozpoczynam nawigację.")

            // --- Zaktualizuj stany markerów ---
            // Punkt o indexie 0 jest celem przed kliknięciem Start (currentPointIndex = 0).
            // Po kliknięciu Start, punkt 0 staje się VISITED. Nowym celem staje się punkt o indexie 1.
            this.currentPointIndex = 1 // Nowy index celu to 1 (drugi punkt)

            // Sprawdź, czy nowy index celu (1) jest w granicach trasy.
            if (this.currentPointIndex < totalMarkers) {
                // Mamy co najmniej 2 punkty w trasie.
                val firstPointMarker = route.markers[0] // Punkt o indexie 0 (już "odwiedzony" przez Start)
                val newTargetMarker = route.markers[this.currentPointIndex] // Punkt o indexie 1 (nowy cel)

                // --- SPRAWDŹ AUTOMATYCZNĄ ZMIANĘ PIĘTRA (z punktu 0 na punkt 1) ---
                if (newTargetMarker.point.floor != firstPointMarker.point.floor) {
                    // Zmiana piętra wykryta między pierwszym a drugim punktem.
                    Log.d(TAG, "Wykryto zmianę piętra z ${firstPointMarker.point.floor} na ${newTargetMarker.point.floor} po starcie.")
                    this.currentFloor = newTargetMarker.point.floor // Zmień aktualne piętro
                    Log.d(TAG, "Automatyczna zmiana piętra na: $currentFloor.")
                }
                // Jeśli piętro się nie zmienia, currentFloor pozostaje piętrem pierwszego punktu.
                // displayGeographicRoute poniżej użyje poprawny currentFloor.

                // Uaktualnij stany wszystkich markerów na podstawie nowego indexu celu (1)
                updateRouteState(route, this.currentPointIndex)
                // Wyświetl trasę na aktualnym piętrze (Overlay użyje zaktualizowanych stanów)
                displayGeographicRoute(route, this.currentPointIndex)
                // Zaktualizuj widoczność przycisków pięter (na wypadek automatycznej zmiany piętra)
                updateFloorButtonState()

                // --- Zaktualizuj widoczność przycisków nawigacyjnych ---
                startButton.visibility = View.GONE // Start znika
                nextButton.visibility = View.VISIBLE // Dalej się pojawia
                stopButton.visibility = View.VISIBLE // Stop się pojawia


                Log.d(TAG, "Rozpoczęto nawigację. Nowy cel index: ${this.currentPointIndex} (punkt ${this.currentPointIndex + 1}).")


            } else {
                // Trasa ma tylko 1 punkt (index 0). Po Start od razu koniec nawigacji.
                // currentPointIndex == 1 (poza zakresem) oznacza koniec nawigacji.
                Log.d(TAG, "Trasa ma tylko 1 punkt. Koniec nawigacji od razu po starcie.")

                // Uaktualnij stany markerów (punkt 0 stanie się VISITED i END)
                // currentPointIndex = 1 jest poza zakresem, updateRouteState obsłuży to.
                updateRouteState(route, this.currentPointIndex)
                // Wyświetl trasę (punkt 0 będzie VISITED + END)
                displayGeographicRoute(route, this.currentPointIndex)
                // Upewnij się, że piętro wyświetlane to piętro tego jedynego punktu
                this.currentFloor = route.markers.first().point.floor
                updateFloorButtonState() // Zaktualizuj widoczność przycisków pięter


                // --- LOGIKA KOŃCA TRASY (dla trasy 1-punktowej) ---
                Toast.makeText(this, "Trasa zakończona!", Toast.LENGTH_LONG).show()
                // Zaktualizuj stan aplikacji i widoczność przycisków
                currentAppState = AppState.FINISHED_DISPLAYED // Ustaw stan zakończenia
                // Automatyczny reset stanu po krótkim opóźnieniu, żeby użytkownik zobaczył ostatni punkt
                Handler(Looper.getMainLooper()).postDelayed({
                    Log.d(TAG, "Opóźnione wywołanie resetAppState() po zakończeniu trasy (1 punkt).")
                    resetAppState() // Powrót do stanu wyboru trasy
                }, 3000)

                // Zaktualizuj widoczność przycisków (wszystkie znikną, potem resetAppState przywróci START)
                startButton.visibility = View.GONE
                nextButton.visibility = View.GONE
                stopButton.visibility = View.GONE
            }


            // --- Logowanie START ---
            startLogging(route)
            // Logujemy osiągnięcie punktu 0 (który stał się VISITED)
            val arrivedPoint = route.markers.getOrNull(0)?.point // Punkt o indexie 0
            logNavigationButtonClick("START", 0, arrivedPoint) // Logujemy index 0

        }


        nextButton.setOnClickListener {
            Log.d(TAG, "Przycisk DALEJ kliknięty.")

            // Sprawdź, czy jesteśmy w stanie nawigacji (NAVIGATING) i czy jest wybrana trasa.
            // currentPointIndex powinien wskazywać na aktualny cel i być w granicach trasy (< size).
            if (currentAppState != AppState.STARTED || this.selectedRoute == null || this.currentPointIndex < 0 || this.currentPointIndex >= this.selectedRoute!!.markers.size) {
                Log.w(TAG, "Przycisk Dalej kliknięty w nieoczekiwanym stanie lub index celu poza zakresem (${this.currentPointIndex}). Resetuję stan.")
                resetAppState() // W przypadku nieoczekiwanego stanu, resetujemy
                return@setOnClickListener
            }

            val route = this.selectedRoute!!
            val totalMarkers = route.markers.size
            val previousTargetIndex = this.currentPointIndex // Zapisz index PUNKTU, który WŁAŚNIE ZOSTANIE OSIĄGNIĘTY/ODWIEDZONY


            // --- LOGIKA PO KLIKNIĘCIU "DALEJ" ---
            // Aktualny cel (o indexie previousTargetIndex) staje się odwiedzony.
            // Następny punkt (o indexie previousTargetIndex + 1) staje się nowym celem (stan CURRENT).

            // Zwiększ index celu, żeby wskazywał na następny punkt.
            this.currentPointIndex++ // Nowy index celu


            // --- SPRAWDŹ AUTOMATYCZNĄ ZMIANĘ PIĘTRA ---
            // Sprawdzamy między punktem, który właśnie został odwiedzony (previousTargetIndex)
            // a punktem, który staje się nowym celem (currentPointIndex).
            if (this.currentPointIndex < totalMarkers) { // Upewnij się, że nowy index celu jest w granicach trasy

                val pointJustVisited = route.markers[previousTargetIndex].point // Punkt poprzedni
                val newTargetPoint = route.markers[this.currentPointIndex].point // Punkt nowy cel

                if (newTargetPoint.floor != pointJustVisited.floor) {
                    // Zmiana piętra wykryta.
                    Log.d(TAG, "Wykryto zmianę piętra z ${pointJustVisited.floor} na ${newTargetPoint.floor} po kliknięciu Dalej.")
                    this.currentFloor = newTargetPoint.floor // Zmień aktualne piętro
                    Log.d(TAG, "Automatyczna zmiana piętra na: $currentFloor.")
                    // updateRouteState i displayGeographicRoute poniżej użyją nowego currentFloor.
                }
                // Jeśli piętro się nie zmienia, currentFloor pozostaje takie samo.

            } else {
                // Nowy currentPointIndex (po zwiększeniu) jest poza zakresem ( == totalMarkers).
                // Oznacza to, że poprzedni punkt (previousTargetIndex, który jest ostatnim punktem trasy)
                // został właśnie "odwiedzony", a nawigacja się zakończyła.
                Log.d(TAG, "Kliknięto DALEJ, a nowy index celu (${this.currentPointIndex}) jest poza zakresem trasy (${totalMarkers}). Koniec nawigacji.")

                // --- LOGIKA KOŃCA TRASY ---
                Toast.makeText(this, "Trasa zakończona!", Toast.LENGTH_LONG).show()

                // Zaktualizuj stany markerów (ostatni punkt stanie się VISITED i END).
                // currentPointIndex (teraz totalMarkers) jest poza zakresem, co sygnalizuje koniec w updateRouteState.
                updateRouteState(route, this.currentPointIndex)
                // Wyświetl trasę (ostatni punkt będzie VISITED + END).
                // Przekazujemy currentPointIndex = totalMarkers do displayGeographicRoute,
                // co jest używane przez Overlay, żeby wiedzieć o końcu nawigacji.
                displayGeographicRoute(route, this.currentPointIndex)

                // Upewnij się, że piętro wyświetlane to piętro ostatniego punktu trasy
                this.currentFloor = route.markers.last().point.floor
                updateFloorButtonState() // Zaktualizuj widoczność przycisków pięter


                // Zaktualizuj stan aplikacji i widoczność przycisków
                currentAppState = AppState.FINISHED_DISPLAYED // Ustaw stan zakończenia
                // Automatyczny reset stanu po krótkim opóźnieniu, żeby użytkownik zobaczył ostatni punkt
                Handler(Looper.getMainLooper()).postDelayed({
                    Log.d(TAG, "Opóźnione wywołanie resetAppState() po zakończeniu trasy.")
                    resetAppState() // Powrót do stanu wyboru trasy (przywróci przycisk START)
                }, 3000)

                // Zaktualizuj widoczność przycisków (wszystkie znikną, potem resetAppState przywróci START)
                startButton.visibility = View.GONE
                nextButton.visibility = View.GONE
                stopButton.visibility = View.GONE


            } // Koniec if (this.currentPointIndex < totalMarkers)


            // Ta część wykona się TYLKO jeśli NIE JESTEŚMY NA KOŃCU TRASY po kliknięciu Dalej
            if (currentAppState == AppState.STARTED) { // Sprawdzamy, czy stan nie zmienił się na ROUTE_COMPLETED

                // Uaktualnij stany wszystkich markerów na podstawie nowego currentPointIndex
                updateRouteState(route, this.currentPointIndex)
                // Wyświetl trasę na aktualnym piętrze (Overlay użyje zaktualizowanych stanów i nowego currentFloor)
                displayGeographicRoute(route, this.currentPointIndex)
                // Zaktualizuj widoczność przycisków pięter (na wypadek automatycznej zmiany piętra)
                updateFloorButtonState()

                // TODO: Ewentualna logika po przejściu do następnego punktu/piętra (np. komunikat)
                // Komunikat powinien informować o dotarciu do previousTargetIndex i celu na newTargetIndex.
                // Znajdź marker, który jest nowym celem
                val newTargetMarker = route.markers.getOrNull(this.currentPointIndex)
                if (newTargetMarker != null) {
                    Toast.makeText(this, "Idź do punktu ${this.currentPointIndex + 1} (piętro ${newTargetMarker.point.floor})", Toast.LENGTH_SHORT).show()
                }


                // Zaktualizuj widoczność przycisków - już zaktualizowane w if/else
                // jeśli currentPointIndex == totalMarkers - 1 (przedostatni punkt), to next znika, stop pojawia się.
                // Jeśli currentPointIndex < totalMarkers - 1, next i stop są już widoczne.
                if (this.currentPointIndex == totalMarkers - 1) {
                    // Jesteśmy przy ostatnim punkcie trasy (index size - 1)
                    nextButton.visibility = View.GONE // Dalej znika
                    stopButton.visibility = View.VISIBLE // Stop się pojawia (oznacza "dotarłem do ostatniego")
                    Log.d(TAG, "Dotarto do ostatniego punktu trasy (index ${this.currentPointIndex}). Pokaż przycisk STOP.")
                    // Zaktualizuj stan aplikacji na "Gotowy do Stop"
                    // currentAppState = AppState.READY_FOR_STOP // Możemy zdefiniować taki stan jeśli potrzebny, ale NAVIGATING też może działać.
                    // Na razie pozostaniemy w NAVIGATING do momentu kliknięcia STOP.
                } else {
                    // Nadal w środku trasy
                    nextButton.visibility = View.VISIBLE
                    stopButton.visibility = View.VISIBLE // Stop zawsze widoczny podczas nawigacji
                }
            }


            // --- Logowanie DALEJ ---
            // Logujemy osiągnięcie punktu previousTargetIndex (który stał się VISITED)
            val arrivedPoint = route.markers.getOrNull(previousTargetIndex)?.point // Punkt o indexie previousTargetIndex
            logNavigationButtonClick("NEXT", previousTargetIndex, arrivedPoint) // Logujemy index tego, do którego dotarliśmy


        }


        stopButton.setOnClickListener {
            Log.d(TAG, "Przycisk STOP kliknięty.")

            // Przycisk Stop jest widoczny podczas nawigacji (po Start)
            // i oznacza "Potwierdzam dotarcie do ostatniego punktu (currentPointIndex)".
            // currentPointIndex W TYM MOMENCIE wskazuje na index OSTATNIEGO PUNKTU TRASY (size - 1),
            // jeśli dotarliśmy do końca normalnie.
            // Jeśli kliknięto Stop w środku trasy, currentPointIndex wskazuje na ostatni osiągnięty cel.
            // Decydujemy, że kliknięcie STOP ZAWSZE przerywa nawigację i wraca do stanu początkowego.

            if (currentAppState != AppState.STARTED && currentAppState != AppState.READY_FOR_STOP) {
                Log.w(TAG, "Przycisk Stop kliknięty w nieoczekiwanym stanie (${currentAppState}) lub brak trasy.")
                // W przypadku błędu, resetujemy do stanu początkowego
                resetAppState()
                return@setOnClickListener
            }
            if (this.selectedRoute == null) {
                Log.w(TAG, "Przycisk Stop kliknięty, ale brak wybranej trasy.")
                resetAppState()
                return@setOnClickListener
            }


            val route = this.selectedRoute!!

            // --- LOGIKA PO KLIKNIĘCIU "STOP" ---
            // Nawigacja jest przerywana.

            // Zaloguj zdarzenie "STOP", index OSTATNIEGO osiągniętego celu (currentPointIndex), i jego współrzędne.
            // Jeśli kliknięto Stop w środku trasy, logujemy ostatni PRAWIDŁOWY index celu.
            // Jeśli kliknięto Stop na końcu, logujemy index ostatniego punktu (size - 1).
            val lastReachedIndexForLog = if (this.currentPointIndex < route.markers.size) this.currentPointIndex else route.markers.size - 1
            val arrivedPointForLog = route.markers.getOrNull(lastReachedIndexForLog)?.point
            logNavigationButtonClick("STOP", lastReachedIndexForLog, arrivedPointForLog)


            Log.i(TAG, "Nawigacja zatrzymana dla trasy: ${route.name} przy punkcie ${lastReachedIndexForLog + 1}.")

            stopLogging() // Zatrzymaj logowanie danych czujników/WiFi


            // --- Resetowanie stanu aplikacji i UI ---
            // Resetujemy index celu i stan aplikacji.
            // displayGeographicRoute w resetAppState użyje indexu 0.
            resetAppState() // Ta metoda ustawi currentPointIndex na 0 i odświeży UI

            Toast.makeText(this, "Nawigacja zatrzymana.", Toast.LENGTH_SHORT).show()


            // Nie potrzebujemy tu już ręcznego zmieniania widoczności przycisków ani displayGeographicRoute,
            // ponieważ resetAppState() to robi.
            // Nie potrzebujemy też opóźnienia, chyba że chcemy, żeby komunikat "Nawigacja zatrzymana" był widoczny dłużej.
            // Handler(Looper.getMainLooper()).postDelayed({
            //     Log.d(TAG, "Opóźnione wywołanie resetAppState() po Stop.")
            //     resetAppState()
            // }, 3000)

        } // Koniec listenera stopButton
    }

    private fun showDeleteConfirmationDialog(routeNameToDelete: String) {
        AlertDialog.Builder(this)
            .setTitle("Potwierdź usunięcie")
            .setMessage("Czy na pewno chcesz usunąć trasę '$routeNameToDelete'? Tej operacji nie można cofnąć.")
            .setPositiveButton("Usuń") { dialog, which ->
                // --- Logika USUWANIA po potwierdzeniu ---
                Log.d(TAG, "Potwierdzono usunięcie trasy: $routeNameToDelete")
                // Wywołaj funkcję obsługującą usuwanie trasy (ta, którą częściowo już masz)
                handleDeleteRoute(routeNameToDelete) // TODO: Upewnij się, że ta funkcja działa poprawnie (wywołuje RouteStorage.deleteRoute, przeładowuje listę, resetuje UI)

                // Po usunięciu, UI głównego ekranu zostanie zaktualizowane przez handleDeleteRoute,
                // a dialog potwierdzenia zamknie się automatycznie.
            }
            .setNegativeButton("Anuluj", null) // Przygotuj przycisk Anuluj
            .setIcon(android.R.drawable.ic_dialog_alert) // Opcjonalnie: dodaj ikonę ostrzeżenia
            .show()
    }

    private fun handleDeleteRoute(routeName: String) {
        try {
            // 1. Usuń trasę z pamięci trwałej
            RouteStorage.deleteRoute(this, routeName)

            // 2. Przeładuj listę tras w MainActivity
            routes = RouteStorage.loadRoutes(this)
            Log.d(TAG, "Trasy przeładowane po usunięciu. Liczba tras: ${routes.size}")


            // 3. Sprawdź, czy usunięto aktualnie wybraną/wyświetlaną trasę
            // Porównaj po nazwie, bo obiekt selectedRoute może wskazywać na starą wersję
            if (selectedRoute != null && selectedRoute?.name == routeName) {
                Log.d(TAG, "Usunięto aktualnie wybraną trasę '${routeName}'. Resetowanie UI.")
                // Jeśli usunięto aktualną trasę, zresetuj stan aplikacji i UI.
                resetAppState() // Ta metoda już czyści overlay i przywraca stan IDLE/przyciski początkowe
                Toast.makeText(
                    this,
                    "Usunięto aktualnie wyświetlaną trasę: $routeName",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                // Usunięto inną trasę. Wystarczy poinformować użytkownika.
                Log.d(TAG, "Usunięto trasę '${routeName}' (nieaktualnie wybraną).")
                Toast.makeText(this, "Usunięto trasę: $routeName", Toast.LENGTH_SHORT).show()
            }

            // Opcjonalnie: Jeśli dialog wyboru jest nadal otwarty, można go odświeżyć,
            // ale obecna logika zamyka dialog po kliknięciu, więc to nie jest problem.

        } catch (e: Exception) {
            Log.e(TAG, "Błąd podczas usuwania trasy: $routeName", e)
            Toast.makeText(this, "Błąd podczas usuwania trasy: $routeName", Toast.LENGTH_SHORT)
                .show()
            // W przypadku błędu, lista 'routes' w MainActivity może być nieaktualna.
            // Można rozważyć przeładowanie jej nawet tutaj: routes = RouteStorage.loadRoutes(this)
        }
    }

    private fun logNavigationButtonClick(
        eventType: String,
        pointIndex: Int,
        targetPoint: MapPoint?
    ) {
        // Pobierz aktualny timestamp
        val timestamp = SimpleDateFormat("MMddHHmmss.SSS", Locale.getDefault()).format(Date())

        // Przygotuj dane punktu jako stringi
        val coordXString = targetPoint?.x?.toString() ?: "NULL"
        val coordYString = targetPoint?.y?.toString() ?: "NULL"

        // Stwórz tablicę stringów z wszystkimi danymi
        val logEntry = arrayOf(
            timestamp,          // Index 0: Timestamp
            eventType,          // Index 1: Typ zdarzenia
            pointIndex.toString(), // Index 2: Index punktu jako string
            coordXString,       // Index 3: Współrzędna X jako string
            coordYString        // Index 4: Współrzędna Y jako string
        )

        // Dodaj tablicę do listy allData
        allData.add(logEntry)

        Log.d(
            TAG,
            "Zalogowano kliknięcie do allData: ${logEntry.joinToString()}"
        ) // Loguj do Logcat dla debugowania
    }

    private fun resetAppState() {

        supportActionBar?.setTitle("Pomiar Trasy")
        // Zresetuj zmienne śledzące trasę
        this.selectedRoute = null
        this.currentPointIndex = 0

        // Ustaw przyciski do stanu początkowego
        startButton.visibility = View.VISIBLE
        startButton.isEnabled = false
        nextButton.visibility = View.GONE
        stopButton.visibility = View.GONE
        floorButtonsContainer.visibility = View.VISIBLE

        // Ustaw stan aplikacji na IDLE
        currentAppState = AppState.IDLE
        Log.d(TAG, "Stan aplikacji: ${currentAppState}. Reset zakończony.")

        // Wyczyść wyświetlanie trasy na RouteOverlayView
        routeOverlayView.clearRoute() // Wywołaj metodę RouteOverlayView do czyszczenia
    }


    // --- Obsługa Uprawnień ---
    private fun hasRequiredPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestMissingPermissions() {
        val missingPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (missingPermissions.isNotEmpty()) {
            Log.i(TAG, "Requesting missing permissions: ${missingPermissions.joinToString()}")
            ActivityCompat.requestPermissions(this, missingPermissions, REQUEST_PERMISSIONS_CODE)
        } else {
            Log.d(TAG, "All required permissions already granted.")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            val granted = hasRequiredPermissions()
            startButton.isEnabled = granted // Aktualizuj stan przycisku START
            if (granted) {
                Log.i(TAG, "All required permissions granted after request.")
                Toast.makeText(this, "Uprawnienia przyznane.", Toast.LENGTH_SHORT).show()
                // Sprawdź ponownie stan Bluetooth po przyznaniu uprawnień
                if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
                    Toast.makeText(this, "Pamiętaj, aby włączyć Bluetooth.", Toast.LENGTH_LONG)
                        .show()
                } else if (bluetoothLeScanner == null) {
                    bluetoothLeScanner =
                        bluetoothAdapter?.bluetoothLeScanner // Spróbuj ponownie zainicjować skaner
                }
            } else {
                Log.w(TAG, "Not all required permissions were granted.")
                Toast.makeText(
                    this,
                    "Nie przyznano wszystkich wymaganych uprawnień. Funkcjonalność ograniczona.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }


    // --- Kontrola Logowania ---

    private fun startLogging(route: Route) {
        if (isLogging) {
            Log.w(TAG, "Logowanie jest już aktywne.")
            return
        }
        // Sprawdź ponownie uprawnienia
        if (!hasRequiredPermissions()) {
            Log.e(TAG, "Nie można rozpocząć logowania - brak uprawnień.")
            Toast.makeText(this, "Brak uprawnień do rozpoczęcia logowania.", Toast.LENGTH_SHORT)
                .show()
            requestMissingPermissions()
            return
        }
        // Sprawdź WiFi
        if (!wifiManager.isWifiEnabled) {
            Log.w(TAG, "WiFi jest wyłączone. Skanowanie WiFi nie będzie działać.")
            Toast.makeText(this, "Włącz WiFi, aby skanować sieci.", Toast.LENGTH_SHORT).show()
            // Logowanie będzie kontynuowane dla BLE i kroków, jeśli są dostępne
        }
        // Sprawdź Bluetooth
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Nie można rozpocząć skanowania BLE - brak adaptera Bluetooth.")
            Toast.makeText(this, "Brak adaptera Bluetooth.", Toast.LENGTH_SHORT).show()
            return // Zablokuj start, jeśli kluczowy element (BLE) jest niemożliwy
        }
        if (!bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "Nie można rozpocząć skanowania BLE - Bluetooth wyłączony.")
            Toast.makeText(
                this,
                "Włącz Bluetooth, aby skanować urządzenia BLE.",
                Toast.LENGTH_SHORT
            ).show()
            // Można zdecydować czy kontynuować tylko z WiFi/krokami, czy zatrzymać
            return // Zablokuj start, jeśli BLE jest wymagane
        }
        if (bluetoothLeScanner == null) {
            bluetoothLeScanner =
                bluetoothAdapter?.bluetoothLeScanner // Spróbuj ponownie zainicjować
            if (bluetoothLeScanner == null) {
                Log.e(TAG, "Nie można uzyskać BluetoothLeScanner.")
                Toast.makeText(this, "Nie można zainicjować skanera BLE.", Toast.LENGTH_SHORT)
                    .show()
                return
            }
        }

        // Sprawdź sensory
        if (accelerometerSensor == null) {
            Log.w(TAG, "Brak sensora accelerometru.")
            Toast.makeText(this, "Brak sensora accelerometru.", Toast.LENGTH_SHORT).show()
        }
        if (gyroscopeSensor == null) {
            Log.w(TAG, "Brak sensora giroskopa.")
            Toast.makeText(this, "Brak sensora giroskopa.", Toast.LENGTH_SHORT).show()
        }
        if (magnetometerSensor == null) {
            Log.w(TAG, "Brak sensora magnetometru.")
            Toast.makeText(this, "Brak sensora magnetometru.", Toast.LENGTH_SHORT).show()
        }
        if (barometerSensor == null) {
            Log.w(TAG, "Brak sensora barometru.")
            Toast.makeText(this, "Brak sensora barometru.", Toast.LENGTH_SHORT).show()
        }

        val safeRouteName = route.name.replace(Regex("[^a-zA-Z0-9-_.]"), "_")
        val timestampFolder =
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val folderName = "${safeRouteName}_log_$timestampFolder"
        // Tworzymy ścieżkę względną wymaganą przez MediaStore
        // np. "Download/log_20250405_183000"
        val relativeFolderPath = Environment.DIRECTORY_DOWNLOADS + "/" + folderName

        // Zapisujemy ścieżkę w zmiennej członkowskiej, aby była dostępna przy zapisie
        currentRelativeLogPath = relativeFolderPath
        Log.i(TAG, "Ustawiono ścieżkę zapisu na: $currentRelativeLogPath")


        Log.i(TAG, "Rozpoczynanie logowania (WiFi, BLE, Kroki)...")
        isLogging = true
        startButton.isEnabled = false
        stopButton.isEnabled = true

        // Czyszczenie danych i dodawanie nagłówków
        wifiData.clear()
        bleData.clear()
        accelerometerData.clear()
        gyroscopeData.clear()
        magnetometerData.clear()
        barometerData.clear()
        allData.clear()
        wifiData.add(arrayOf("Timestamp", "scanType", "BSSID", "SSID", "RSSI", "Frequency"))
        bleData.add(arrayOf("Timestamp", "scanType", "DeviceName", "DeviceAddress", "RSSI"))
        accelerometerData.add(arrayOf("Timestamp", "scanType", "AccX", "AccY", "AccZ"))
        gyroscopeData.add(arrayOf("Timestamp", "scanType", "GyroX", "GyroY", "GyroZ"))
        magnetometerData.add(arrayOf("Timestamp", "scanType", "MagX", "MagY", "MagZ"))
        barometerData.add(arrayOf("Timestamp", "scanType", "Pressure"))


        // Start skanowania WiFi
        if (wifiManager.isWifiEnabled) {
            if (!triggerWifiScan()) {
                Log.e(TAG, "Nie udało się zainicjować pierwszego skanowania WiFi.")
                Toast.makeText(this, "Błąd startu skanowania WiFi.", Toast.LENGTH_SHORT).show()
            }
        }

        // Start skanowania BLE
        startBleScan()

        // Rejestracja sensorów
        registerSensors()


        Toast.makeText(this, "Rozpoczęto logowanie.", Toast.LENGTH_SHORT).show()
    }

    private fun stopLoggingInternal(saveData: Boolean) {
        if (!isLogging) {
            // Już zatrzymane lub nigdy nie uruchomione
            Log.d(TAG, "stopLoggingInternal wywołane, gdy nie logowano.")
            startButton.isEnabled = hasRequiredPermissions()
            stopButton.isEnabled = false
            return
        }
        Log.i(TAG, "Zatrzymywanie wewnętrznych procesów logowania...")
        isLogging = false

        handler.removeCallbacksAndMessages(null)
        stopBleScan()
        sensorManager.unregisterListener(this)

        startButton.isEnabled = hasRequiredPermissions()
        stopButton.isEnabled = false

        val targetPath = currentRelativeLogPath ?: Environment.DIRECTORY_DOWNLOADS

        if (saveData) {
            Log.i(TAG, "Rozpoczynanie zapisu danych...")
            if (wifiData.size > 1) { // Więcej niż tylko nagłówek
                try {
                    writeCsvData(this, wifiData, "wifi_log", targetPath)
                } catch (e: IOException) {
                    Log.e(TAG, "Błąd zapisu pliku CSV dla WiFi", e)
                    Toast.makeText(this, "Błąd zapisu danych WiFi: ${e.message}", Toast.LENGTH_LONG)
                        .show()
                }
            } else {
                Log.i(TAG, "Brak danych WiFi do zapisania.")
            }

            // Sprawdź i zapisz dane BLE
            if (bleData.size > 1) {
                try {
                    writeCsvData(this, bleData, "ble_log", targetPath)
                } catch (e: IOException) {
                    Log.e(TAG, "Błąd zapisu pliku CSV dla BLE", e)
                    Toast.makeText(this, "Błąd zapisu danych BLE: ${e.message}", Toast.LENGTH_LONG)
                        .show()
                }
            } else {
                Log.i(TAG, "Brak danych BLE do zapisania.")
            }

            if (accelerometerData.size > 1) {
                try {
                    writeCsvData(this, accelerometerData, "accelerometer_log", targetPath)
                } catch (e: IOException) {
                    Log.e(TAG, "Błąd zapisu pliku CSV dla akcelerometru", e)
                    Toast.makeText(
                        this,
                        "Błąd zapisu danych akcelerometru: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                Log.i(TAG, "Brak danych z akcelerometru do zapisania.")
            }

            // Sprawdź i zapisz dane z żyroskopu
            if (gyroscopeData.size > 1) {
                try {
                    writeCsvData(this, gyroscopeData, "gyroscope_log", targetPath)
                } catch (e: IOException) {
                    Log.e(TAG, "Błąd zapisu pliku CSV dla żyroskopu", e)
                    Toast.makeText(
                        this,
                        "Błąd zapisu danych żyroskopu: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                Log.i(TAG, "Brak danych z żyroskopu do zapisania.")
            }

            // Sprawdź i zapisz dane z magnetometru
            if (magnetometerData.size > 1) {
                try {
                    writeCsvData(this, magnetometerData, "magnetometer_log", targetPath)
                } catch (e: IOException) {
                    Log.e(TAG, "Błąd zapisu pliku CSV dla magnetometru", e)
                    Toast.makeText(
                        this,
                        "Błąd zapisu danych magnetometru: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                Log.i(TAG, "Brak danych z magnetometru do zapisania.")
            }

            // Sprawdź i zapisz dane z barometru
            if (barometerData.size > 1) {
                try {
                    writeCsvData(this, barometerData, "barometer_log", targetPath)
                } catch (e: IOException) {
                    Log.e(TAG, "Błąd zapisu pliku CSV dla barometru", e)
                    Toast.makeText(
                        this,
                        "Błąd zapisu danych barometru: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                Log.i(TAG, "Brak danych z barometru do zapisania.")
            }

            if (allData.size > 1) {
                try {
                    writeCsvData(this, allData, "allData", targetPath)
                } catch (e: IOException) {
                    Log.e(TAG, "Błąd zapisu pliku CSV dla allData", e)
                    Toast.makeText(
                        this,
                        "Błąd zapisu danych allData: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                Log.i(TAG, "Brak danych z allData do zapisania.")
            }


            Toast.makeText(
                this,
                "Zatrzymano logowanie. Dane zapisane (jeśli zebrano).",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(this, "Zatrzymano logowanie (bez zapisu).", Toast.LENGTH_SHORT).show()
        }

        // Zawsze czyść dane po zatrzymaniu (czy zapisano, czy nie)
        wifiData.clear()
        bleData.clear()
        accelerometerData.clear()
        gyroscopeData.clear()
        magnetometerData.clear()
        barometerData.clear()
    }

    private fun stopLogging() {
        Log.i(TAG, "Wywołano stopLogging (z zapisem).")
        stopLoggingInternal(saveData = true) // Zatrzymaj i zapisz dane
    }


    // --- Obsługa Skanowania WiFi ---

    private fun triggerWifiScan(): Boolean {
        if (!checkWifiPermissions()) {
            Log.w("myWifiScan", "WiFi: Brak wymaganych uprawnień.")
            stopLoggingInternal(saveData = false)
            return false
        }

        if (!wifiManager.isWifiEnabled) {
            Log.w("myWifiScan", "WiFi: Próba skanowania przy wyłączonym WiFi.")
            Toast.makeText(this, "WiFi wyłączone, skanowanie WiFi pominięte.", Toast.LENGTH_SHORT).show()
            return false
        }

        return try {
            Log.d("myWifiScan", "WiFi: Wywołanie wifiManager.startScan()")
            val started = wifiManager.startScan()
            if (!started) {
                Log.w("myWifiScan", "WiFi: wifiManager.startScan() zwrócił false. Możliwe throttling.")
            }
            started
        } catch (se: SecurityException) {
            Log.e("myWifiScan", "WiFi: SecurityException podczas startScan.", se)
            stopLoggingInternal(saveData = false)
            false
        } catch (e: Exception) {
            Log.e("myWifiScan", "WiFi: Wyjątek podczas startScan.", e)
            false
        }
    }

    private fun checkWifiPermissions(): Boolean {
        val permissions = listOf(
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun processWifiScanResults() {
        if (!checkWifiPermissions()) {
            Log.e(TAG, "WiFi: Brak uprawnień do pobrania wyników skanowania.")
            stopLoggingInternal(saveData = false)
            return
        }

        try {
            val scanResults = wifiManager.scanResults
            if (scanResults.isNullOrEmpty()) {
                Log.w(TAG, "WiFi: Brak wyników skanowania.")
                return
            }

            val timestamp = SimpleDateFormat("MMddHHmmss.SSS", Locale.getDefault()).format(Date())
            scanResults.forEach { result ->
                if (!result.BSSID.isNullOrEmpty()) {
                    val data = arrayOf(
                        timestamp,
                        "WiFi",
                        result.BSSID,
                        result.SSID ?: "<Brak SSID>",
                        result.level.toString(),
                        result.frequency.toString()
                    )
                    wifiData.add(data)
                    allData.add(data)
                }
            }

            Log.d(TAG, "WiFi: Dodano ${scanResults.size} wyników do listy.")

        } catch (se: SecurityException) {
            Log.e(TAG, "WiFi: SecurityException podczas pobierania/przetwarzania wyników.", se)
            stopLoggingInternal(saveData = false)
        } catch (e: Exception) {
            Log.e(TAG, "WiFi: Wyjątek podczas przetwarzania wyników.", e)
            Toast.makeText(this, "Błąd przetwarzania wyników WiFi.", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Obsługa Skanowania BLE ---

    private fun startBleScan() {
        if (!isLogging) return // Nie startuj, jeśli logowanie zostało zatrzymane

        // Sprawdź uprawnienia specyficzne dla BLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "BLE: Brak uprawnienia BLUETOOTH_SCAN.")
                Toast.makeText(this, "Brak uprawnienia do skanowania BLE.", Toast.LENGTH_SHORT)
                    .show()
                stopLoggingInternal(false) // Zatrzymaj, bo kluczowe uprawnienie brakuje
                return
            }
        } else {
            // Starsze wersje wymagają BLUETOOTH_ADMIN i lokalizacji (już sprawdzane w startLogging)
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_ADMIN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "BLE: Brak uprawnienia BLUETOOTH_ADMIN.")
                Toast.makeText(this, "Brak uprawnienia admina Bluetooth.", Toast.LENGTH_SHORT)
                    .show()
                stopLoggingInternal(false)
                return
            }
        }
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(
                TAG,
                "BLE: Brak uprawnienia ACCESS_FINE_LOCATION - skanowanie może nie działać poprawnie."
            )
            // Skanowanie może nadal działać bez lokalizacji, ale często jest wymagane
            // Toast.makeText(this,"Brak uprawnienia lokalizacji, skanowanie BLE może być ograniczone.",Toast.LENGTH_LONG).show()
        }


        if (bluetoothLeScanner == null) {
            Log.e(TAG, "BLE: Skaner jest null. Nie można rozpocząć skanowania.")
            return
        }

        // Definicja ustawień i filtrów skanowania (opcjonalnie)
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // Intensywne skanowanie
            // .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES) // Otrzymuj każdy wynik
            // .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            // .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0) // Raportuj natychmiast (0) lub w batchach (>0 ms)
            .build()

        val scanFilters: MutableList<ScanFilter> = ArrayList() // Pusta lista = skanuj wszystko

        // szukanie po nazwie urzadzenia "PW" -> beacony w gmachu glownym
        val filterByName = ScanFilter.Builder()
            .setDeviceName("PW")
            .build()
        scanFilters.add(filterByName)

        try {
            Log.d(TAG, "BLE: Rozpoczynanie skanowania LE...")
            bluetoothLeScanner?.startScan(scanFilters, scanSettings, bleScanCallback)
        } catch (se: SecurityException) {
            Log.e(TAG, "BLE: SecurityException podczas startScan.", se)
            Toast.makeText(this, "Błąd uprawnień przy starcie skanowania BLE.", Toast.LENGTH_SHORT)
                .show()
            stopLoggingInternal(false)
        } catch (e: Exception) {
            Log.e(TAG, "BLE: Wyjątek podczas startScan.", e)
            Toast.makeText(
                this,
                "Nieoczekiwany błąd przy starcie skanowania BLE.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun stopBleScan() {
        // Sprawdź uprawnienia przed próbą zatrzymania
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(
                    TAG,
                    "BLE: Brak uprawnienia BLUETOOTH_SCAN do zatrzymania skanowania (ale próbuję)."
                )
                // Teoretycznie stopScan może nie wymagać uprawnienia, ale lepiej być ostrożnym
            }
        }

        if (bluetoothLeScanner != null && bluetoothAdapter?.isEnabled == true) { // Sprawdź czy adapter włączony
            try {
                Log.d(TAG, "BLE: Zatrzymywanie skanowania LE...")
                bluetoothLeScanner?.stopScan(bleScanCallback)
            } catch (se: SecurityException) {
                Log.e(TAG, "BLE: SecurityException podczas stopScan.", se)
                // Mało prawdopodobne, ale możliwe
            } catch (e: Exception) {
                Log.e(TAG, "BLE: Wyjątek podczas stopScan.", e)
            }
        } else {
            Log.d(TAG, "BLE: Skaner null lub Bluetooth wyłączony, nie ma czego zatrzymywać.")
        }
    }

    private fun processBleScanResult(result: ScanResult) {
        // Potrzebne uprawnienie CONNECT do pobrania nazwy na API 31+
        var deviceName = "<Brak nazwy>"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(
                    TAG,
                    "BLE: Brak uprawnienia BLUETOOTH_CONNECT, nie można pobrać nazwy urządzenia ${result.device.address}."
                )
                deviceName = "<Brak uprawnień>"
            }
        }
        // Na starszych API lub jeśli mamy uprawnienie CONNECT:
        if (deviceName == "<Brak nazwy>" || deviceName == "<Brak uprawnień>") {
            try {
                // Sprawdzenie uprawnienia BLUETOOTH (ogólne) lub CONNECT (API 31+) jest niejawnie wymagane przez getName()
                deviceName = result.device.name ?: "<Brak nazwy>"
            } catch (se: SecurityException) {
                Log.w(
                    TAG,
                    "BLE: SecurityException przy próbie pobrania nazwy dla ${result.device.address}.",
                    se
                )
                deviceName = "<Brak uprawnień>"
            }
        }


        val deviceAddress = result.device.address ?: "<Brak adresu>"
        val rssi = result.rssi.toString()
        val timestamp = SimpleDateFormat("MMddHHmmss.SSS", Locale.getDefault()).format(Date())

        Log.v(
            TAG,
            "BLE: Znaleziono urządzenie: Adres=${deviceAddress}, Nazwa=${deviceName}, RSSI=${rssi}"
        ) // Użyj Verbose dla częstych logów

        val data = arrayOf(
            timestamp,
            "BLE",
            deviceName,
            deviceAddress,
            rssi,
        )
        bleData.add(data)
        allData.add(data)
    }


    // --- Obsługa Sensorów ---

    private fun registerSensors() {

        val samplingRate = SensorManager.SENSOR_DELAY_NORMAL

        accelerometerSensor?.let {
            Log.d(TAG, "Sensory: Rejestrowanie sensora przyspieszenia.")
            sensorManager.registerListener(this, it, samplingRate)
        } ?: Log.w(TAG, "Sensory: Sensor przyspieszenia nie jest dostępny do rejestracji.")

        gyroscopeSensor?.let {
            Log.d(TAG, "Sensory: Rejestrowanie sensora żyroskopu.")
            sensorManager.registerListener(this, it, samplingRate)
        } ?: Log.w(TAG, "Sensory: Sensor żyroskopu nie jest dostępny do rejestracji.")

        magnetometerSensor?.let {
            Log.d(TAG, "Sensory: Rejestrowanie sensora pola magnetycznego.")
            sensorManager.registerListener(this, it, samplingRate)
        } ?: Log.w(TAG, "Sensory: Sensor pola magnetycznego nie jest dostępny do rejestracji.")

        barometerSensor?.let {
            Log.d(TAG, "Sensory: Rejestrowanie sensora ciśnienia.")
            sensorManager.registerListener(this, it, samplingRate)
        } ?: Log.w(TAG, "Sensory: Sensor ciśnienia nie jest dostępny do rejestracji.")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isLogging || event == null) return // Ignoruj, jeśli nie logujemy lub zdarzenie jest null

        val timestamp = SimpleDateFormat("MMddHHmmss.SSS", Locale.getDefault()).format(Date())


        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                //Log.v(TAG, "Akcelerometr: X=$x, Y=$y, Z=$z")
                val data = arrayOf(
                    timestamp,
                    "Accelerometer",
                    x.toString(),
                    y.toString(),
                    z.toString()
                )
                accelerometerData.add(data)
                allData.add(data)
            }

            Sensor.TYPE_GYROSCOPE -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                //Log.v(TAG, "Żyroskop: X=$x, Y=$y, Z=$z")
                val data = arrayOf(
                    timestamp,
                    "Gyroscope",
                    x.toString(),
                    y.toString(),
                    z.toString()
                )
                gyroscopeData.add(data)
                allData.add(data)
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                //Log.v(TAG, "Magnetometr: X=$x, Y=$y, Z=$z")
                val data = arrayOf(
                    timestamp,
                    "Magnetometer",
                    x.toString(),
                    y.toString(),
                    z.toString(),
                )
                magnetometerData.add(data)
                allData.add(data)
            }

            Sensor.TYPE_PRESSURE -> {
                val pressure = event.values[0]
                //Log.v(TAG, "Barometr: Ciśnienie=$pressure")
                val data = arrayOf(
                    timestamp,
                    pressure.toString()
                )
                barometerData.add(data)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Można dodać logowanie zmian dokładności, jeśli potrzebne
        // Log.d(TAG, "Sensor ${sensor?.name} - zmiana dokładności na: $accuracy")
    }


    // --- Zapis do Plików CSV (Uogólniona funkcja) ---
    @Throws(IOException::class)
    private fun writeCsvData(
        context: Context,
        dataList: List<Array<String>>,
        filePrefix: String,
        relativeDirectoryPath: String
    ) {
        if (dataList.size <= 1) {
            Log.i(TAG, "Brak danych do zapisania dla prefiksu: $filePrefix.")
            return
        }

        // Sprawdzamy, czy ścieżka nie jest pusta (choć powinna być ustawiona w startLogging)
        if (relativeDirectoryPath.isBlank()) {
            Log.e(TAG, "Ścieżka względna do zapisu jest pusta! Zapisuję bezpośrednio w Downloads.")
            // Można tu albo przerwać, albo zapisać domyślnie w Downloads
            // W tym przykładzie zapiszemy w Downloads jako fallback
            writeCsvData(
                context,
                dataList,
                filePrefix,
                Environment.DIRECTORY_DOWNLOADS
            ) // Wywołanie rekurencyjne z domyślną ścieżką
            return
        }


        Log.d(
            TAG,
            "Próba zapisu ${dataList.size} wierszy do CSV dla prefiksu: $filePrefix w folderze: $relativeDirectoryPath"
        )
        val resolver = context.contentResolver
        val timestampFile = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        // Nazwa pliku pozostaje taka sama (prefix + timestamp)
        val displayName = "${filePrefix}_$timestampFile.csv"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            // TUTAJ ZMIANA: Używamy przekazanej ścieżki względnej
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDirectoryPath)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        var uri = try {
            // Używamy nadal EXTERNAL_CONTENT_URI, bo RELATIVE_PATH określa lokalizację
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Nie udało się utworzyć wpisu MediaStore dla $displayName w $relativeDirectoryPath.",
                e
            )
            Toast.makeText(context, "Błąd tworzenia pliku $displayName.", Toast.LENGTH_LONG).show()
            null
        }

        if (uri == null) {
            Log.e(TAG, "URI MediaStore jest null, nie można zapisać pliku $displayName.")
            return
        }

        Log.d(TAG, "Próba zapisu do URI MediaStore: $uri dla pliku $displayName")
        var success = false
        try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.bufferedWriter().use { writer ->
                    dataList.forEach { row ->
                        writer.append(row.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" })
                        writer.append("\n")
                    }
                    writer.flush()
                    Log.i(
                        TAG,
                        "Pomyślnie zapisano ${dataList.size} wierszy do $displayName w $relativeDirectoryPath."
                    )
                    success = true
                }
                /*
                //Informujemy użytkownika o pełnej ścieżce względnej
                Toast.makeText(context, "Plik $displayName zapisany w $relativeDirectoryPath.", Toast.LENGTH_LONG).show()
                */

            } ?: run {
                Log.e(TAG, "Nie udało się otworzyć strumienia wyjściowego dla URI: $uri")
                Toast.makeText(
                    context,
                    "Błąd zapisu pliku $displayName (nie można otworzyć strumienia).",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: IOException) {
            // ... (obsługa błędów bez zmian) ...
            Log.e(TAG, "IOException podczas zapisu pliku CSV $displayName", e)
            Toast.makeText(context, "Błąd I/O podczas zapisu $displayName.", Toast.LENGTH_LONG)
                .show()
        } catch (e: Exception) {
            // ... (obsługa błędów bez zmian) ...
            Log.e(TAG, "Nieoczekiwany wyjątek podczas zapisu pliku CSV $displayName", e)
            Toast.makeText(
                context,
                "Nieoczekiwany błąd podczas zapisu $displayName.",
                Toast.LENGTH_LONG
            ).show()
        } finally {
            // ... (finalne operacje na MediaStore - bez zmian, logika zależy od 'success' i 'uri') ...
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, if (success) 0 else 1)
                try {
                    if (success) {
                        resolver.update(uri, contentValues, null, null)
                    } else {
                        Log.w(
                            TAG,
                            "Zapis nie powiódł się, usuwanie wpisu MediaStore dla $displayName."
                        )
                        resolver.delete(uri, null, null)
                    }
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "Nie udało się zaktualizować/usunąć stanu IS_PENDING dla $displayName.",
                        e
                    )
                }
            } else if (!success && uri != null) {
                Log.w(
                    TAG,
                    "Zapis $displayName nie powiódł się na starszym API. Plik może pozostać niekompletny."
                )
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true // Zwróć true, żeby menu było wyświetlone
    }

    // --- DODAJ METODY DO OBSŁUGI MENU TOOLBAR ---

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Logika obsługi kliknięć w zależności od ID elementu menu
        return when (item.itemId) {
            R.id.action_select_route -> {
                // Logika przycisku "Wybierz Trasę"
                handleSelectRouteAction() // Wywołaj metodę z logiką wyboru
                true // Zwróć true, żeby system wiedział, że kliknięcie zostało obsłużone
            }

            R.id.action_add_route -> {
                // Logika przycisku "Dodaj trasę"
                handleCreateRouteAction() // Wywołaj metodę z logiką dodawania
                true
            }

            R.id.action_delete_route -> {
                // Logika przycisku "Usuń Trasę"
                handleDeleteRouteAction() // Wywołaj metodę z logiką usuwania (otwierającą dialog usuwania)
                true
            }

            else -> super.onOptionsItemSelected(item) // Obsłuż inne kliknięcia w menu (np. przycisk "home")
        }
    }

    private fun handleSelectRouteAction() {
        // Przeładuj trasy przed pokazaniem dialogu wyboru
        routes = RouteStorage.loadRoutes(this)
        Log.d(
            TAG,
            "Przeładowano trasy przed pokazaniem dialogu wyboru (z Toolbar). Liczba tras: ${routes.size}"
        )

        if (routes.isEmpty()) {
            Toast.makeText(this, "Brak zapisanych tras do wyboru.", Toast.LENGTH_SHORT).show()
            return
        }

        val routeNames = routes.map { it.name }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Wybierz trasę do wyświetlenia")
            .setItems(routeNames) { dialog, which ->
                val selectedRouteFromDialog = routes[which]
                supportActionBar?.setTitle(selectedRouteFromDialog.name)

                this.selectedRoute = selectedRouteFromDialog

                if (this.selectedRoute != null && this.selectedRoute!!.markers.isNotEmpty()) {
                    val firstPointFloor = this.selectedRoute!!.markers.first().point.floor // Pobierz piętro pierwszego punktu
                    this.currentFloor = firstPointFloor // Ustaw aktualne piętro na piętro pierwszego punktu
                    Log.d(TAG, "Dialog wyboru: Ustawiono początkowe piętro na: ${this.currentFloor} (piętro pierwszego punktu trasy).")
                } else {
                    // Jeśli trasa jest pusta lub null (co nie powinno się zdarzyć po przeładowaniu, ale jako zabezpieczenie)
                    // Ustaw piętro na domyślne lub pierwsze dostępne.
                    this.currentFloor = floorFileMappingPng.keys.firstOrNull() ?: 0 // Ustaw na pierwsze dostępne piętro lub 0
                    Log.w(TAG, "Dialog wyboru: Wybrana trasa jest pusta lub null. Ustawiono piętro na domyślne: ${this.currentFloor}")
                }

                startButton.isEnabled = true

                currentAppState = AppState.ROUTE_SELECTED
                Log.d(
                    TAG,
                    "Stan aplikacji: ${currentAppState}. Wybrano trasę: ${selectedRouteFromDialog.name}"
                )

                // Odśwież widoczność przycisków pięter dla nowego piętra
                updateFloorButtonState()

                this.currentPointIndex = 0
                Log.d(TAG, "Dialog wyboru: Wywołuję displayGeographicRoute dla trasy: ${this.selectedRoute?.name}, index: ${this.currentPointIndex}")

                displayGeographicRoute(this.selectedRoute, this.currentPointIndex)



                dialog.dismiss()
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    private fun handleCreateRouteAction() {
        // Skopiuj całą logikę z wnętrza starego listenera buttonShowCreateRoute.setOnClickListener tutaj
        // Ta logika powinna otwierać Fragment do tworzenia trasy.
        Log.d(TAG, "Akcja 'Dodaj trasę' z Toolbar kliknięta.")

        // Ukryj przyciski nawigacyjne i clearRouteButton podczas tworzenia trasy
        startButton.visibility = View.GONE
        nextButton.visibility = View.GONE
        stopButton.visibility = View.GONE
        floorButtonsContainer.visibility = View.GONE


        // Wyczyść wyświetlanie trasy na overlay podczas tworzenia nowej trasy
        routeOverlayView.clearRoute()

        // Otwórz fragment do tworzenia trasy
        val fragment = CreateRouteFragment()
        val availableFloors = floorFileMappingPng.keys.sorted().toIntArray()
        val args = Bundle().apply {
            // Zapisz listę pięter w Bundle pod kluczem (np. "available_floors")
            putIntArray("available_floors", availableFloors) // Użyj putIntArray dla IntArray
        }
        fragment.arguments = args

        supportFragmentManager.beginTransaction()
            .replace(
                R.id.mainContentContainer,
                fragment
            )
            .addToBackStack(null)
            .commit()
    }

    private fun handleDeleteRouteAction() {
        // Skopiuj całą logikę z wnętrza starego listenera deleteRouteButton.setOnClickListener tutaj
        // Ta logika powinna wczytywać trasy i pokazywać dialog USUWANIA (listę tras do usunięcia).

        Log.d(TAG, "Akcja 'Usuń Trasę' z Toolbar kliknięta.")

        // Przeładuj trasy przed pokazaniem dialogu usuwania
        routes = RouteStorage.loadRoutes(this)
        Log.d(
            TAG,
            "Przeładowano trasy przed pokazaniem dialogu usuwania (z Toolbar). Liczba tras: ${routes.size}"
        )


        if (routes.isEmpty()) {
            Toast.makeText(this, "Brak zapisanych tras do usunięcia.", Toast.LENGTH_SHORT).show()
            return
        }

        val routeNames = routes.map { it.name }.toTypedArray()

        // Pokaż dialog z listą tras do usunięcia
        AlertDialog.Builder(this)
            .setTitle("Wybierz trasę do usunięcia")
            .setItems(routeNames) { dialog, which ->
                // which to indeks wybranej trasy w liście 'routes'
                val routeToDeleteName = routes[which].name

                // --- Pokaż dialog POTWIERDZENIA usunięcia ---
                showDeleteConfirmationDialog(routeToDeleteName) // Wywołaj metodę do pokazania dialogu potwierdzenia

                dialog.dismiss() // Zamknij dialog z listą tras
            }
            .setNegativeButton("Anuluj", null)
            .show()

        // TODO: Upewnij się, że metoda showDeleteConfirmationDialog(routeNameToDelete: String) jest zaimplementowana
        // i że jej pozytywny przycisk wywołuje handleDeleteRoute(routeNameToDelete).
    }

}
