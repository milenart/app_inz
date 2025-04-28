package pl.pw.epicgameproject

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
import androidx.fragment.app.FragmentManager


class MainActivity : AppCompatActivity(), SensorEventListener, FragmentManager.OnBackStackChangedListener {

    // --- Deklaracje widoków z nowego layoutu ---
    private lateinit var floorPlanImageView: ImageView
    private lateinit var routeOverlayView: RouteOverlayView
    private lateinit var startButton: Button
    private lateinit var nextButton: Button
    private lateinit var stopButton: Button

    //    private lateinit var selectRouteButton: Button
//    private lateinit var createRouteButton: Button
//    private lateinit var deleteRouteButton: Button
    private lateinit var toolbar: Toolbar


    // Przykladowa sciezka
    private val some_geo_x_1 = 637245.46
    private val some_geo_y_1 = 485716.91
    private val some_geo_x_2 = 637260.65
    private val some_geo_y_2 = 485717.58
    private val some_geo_x_3 = 637261.27
    private val some_geo_y_3 = 485746.35
    private val some_geo_x_4 = 637225.19
    private val some_geo_y_4 = 485761.54

    private val geographicRoutePoints: List<MapPoint> = listOf(
        MapPoint(some_geo_x_1, some_geo_y_1),
        MapPoint(some_geo_x_2, some_geo_y_2),
        MapPoint(some_geo_x_3, some_geo_y_3),
        MapPoint(some_geo_x_4, some_geo_y_4)
    )

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

    // --- Transformacje ---
    private var paramA: Double = 0.0
    private var paramD: Double = 0.0 // Rotation Y
    private var paramB: Double = 0.0 // Rotation X
    private var paramE: Double = 0.0
    private var paramC: Double = 0.0
    private var paramF: Double = 0.0
    private var worldFileLoaded = false

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
    private var currentPointIndex: Int = -1

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
                    Log.d(TAG, "WiFi: Otrzymano nowe wyniki skanowania.")
                    processWifiScanResults()
                    handler.postDelayed({
                        if (isLogging) triggerWifiScan()
                    }, 500)
                } else {
                    Log.d(TAG, "WiFi: Otrzymano stare (cached) wyniki skanowania.")
                    handler.postDelayed({
                        if (isLogging) triggerWifiScan()
                    }, 1000)
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
        floorPlanImageView.setImageResource(R.drawable.gmach_f0_01)
        routeOverlayView = findViewById(R.id.routeOverlayView)
        toolbar = findViewById(R.id.toolbar)

        startButton = findViewById(R.id.startButton)
        nextButton = findViewById(R.id.nextButton)
        stopButton = findViewById(R.id.stopButton)
//        selectRouteButton = findViewById(R.id.selectRouteButton)
//        createRouteButton = findViewById(R.id.buttonShowCreateRoute)
//        deleteRouteButton = findViewById(R.id.deleteRouteButton)

        setSupportActionBar(toolbar)
        supportActionBar?.setTitle("Pomiar Trasy")

        // Ustawienie Komponentów UI ekranu głównego
        setupMainButtons()

        // Odpalenie parsowania pliku PGW (pozniej do usuniecia)
        loadWorldFileParameters(this, "gmach_f0_01.pgw")

        // Pobranie wszystkich tras
        routes = RouteStorage.loadRoutes(this)

        val staticMarkers = geographicRoutePoints.map { mapPoint ->
            Marker(point = mapPoint, state = MarkerState.PENDING)
        }
        val staticRoute = Route(name = "Trasa Statyczna", markers = staticMarkers)

        routes = routes + staticRoute


        // --- Inicjowanie widoku dla tworzenia trasy
        val inflater = LayoutInflater.from(this)
        val createRouteView = inflater.inflate(R.layout.create_route_view, null)

        // Dodaj go do głównego layoutu (np. FrameLayout)
        val mainLayout = findViewById<FrameLayout>(R.id.mainContentContainer)
        mainLayout.addView(createRouteView)

        // Na początku ukryj ten widok
        createRouteView.visibility = View.GONE

        try {
            val options = BitmapFactory.Options().apply {
                inDensity = 1 // Mówimy Androidowi, żeby traktował zasób jak dla gęstości 1
                inTargetDensity = 1 // Celujemy w gęstość 1
                inScaled = false // NAJWAŻNIEJSZE: Wyłączamy automatyczne skalowanie gęstości
            }
            // Wczytujemy bitmapę z tymi opcjami, żeby dostać jej rzeczywiste wymiary plikowe
            val bitmap = BitmapFactory.decodeResource(resources, R.drawable.gmach_f0_01, options)

            if (bitmap != null) {
                bitmapWidth = bitmap.width // Pobieramy szerokość z obiektu Bitmapy
                bitmapHeight = bitmap.height // Pobieramy wysokość z obiektu Bitmapy
                Log.i(
                    TAG,
                    "ORYGINALNE wymiary Bitmapy (z BitmapFactory.Options): $bitmapWidth x $bitmapHeight"
                )

                // Możesz teraz ustawić tę bitmapę na ImageView, jeśli nie robiłeś tego wcześniej.
                // floorPlanImageView.setImageBitmap(bitmap) // Upewnij się, że ImageView dostaje obraz

                // Zwolnienie zasobów bitmapy, jeśli nie jest używana bezpośrednio przez ImageView
                // Jeśli ustawiasz ją w ImageView (jak w linii powyżej), Android tym zarządza.
                // Jeśli nie, rozważ bitmap.recycle() po pobraniu wymiarów.
                // Ale skoro wcześniej używałeś setImageResource, po prostu kontynuuj z wymiarami.

            } else {
                Log.e(
                    TAG,
                    "Nie udało się wczytać bitmapy z opcjami, aby uzyskać oryginalne wymiary. Wymiary bitmapy nieznane."
                )
                // Obsłuż błąd - np. pokaż Toast, wyłącz funkcjonalność mapową
                bitmapWidth = 0 // Ustaw na 0, żeby uniemożliwić dalsze obliczenia
                bitmapHeight = 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Błąd podczas pobierania oryginalnych wymiarów bitmapy", e)
            bitmapWidth = 0 // Ustaw na 0
            bitmapHeight = 0
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
    }


    /**
     * Przelicza współrzędne mapy (np. EPSG:2178) na współrzędne pikselowe obrazu.
     * Zakłada, że parametry A, B, C, D, E, F zostały wcześniej wczytane z pliku world file.
     * Używa uproszczonego wzoru dla obrazów nieobróconych (B=0, D=0).
     * Zwraca obiekt Point(x, y) z koordynatami pikselowymi lub null w przypadku błędu.
     */
    private fun mapToPixel(mapX: Double, mapY: Double): ScreenPoint? {
        if (!worldFileLoaded) {
            Log.e(TAG, "Cannot perform mapToPixel: World file parameters not loaded.")
            return null
        }

        // Sprawdzenie czy wyznacznik nie jest zbyt bliski zeru (czyli transformacja odwracalna)
        val denominator = paramA * paramE - paramB * paramD
        if (kotlin.math.abs(denominator) < 1e-12) {
            Log.e(TAG, "Cannot perform mapToPixel: Transformation matrix is not invertible.")
            return null
        }

        // Pełny wzór odwrotnej transformacji affine
        val deltaX = mapX - paramC
        val deltaY = mapY - paramF

        val pixelX = (paramE * deltaX - paramB * deltaY) / denominator
        val pixelY = (paramA * deltaY - paramD * deltaX) / denominator

        return ScreenPoint(pixelX.toFloat(), pixelY.toFloat())
    }


    /**
     * Processes a given Route object, converts its markers' points from raw map
     * coordinates (Double) to screen pixel coordinates (Float), and sets it on
     * the RouteOverlayView for display after the view is laid out.
     *
     * @param routeToDisplay The Route object to display, or null to clear the display.
     * Assumes points within this Route are Point(Double, Double) raw map coordinates.
     */
    fun displayGeographicRoute(
        routeToDisplay: Route?,
        currentTargetIndex: Int = -1
    ) { // Przyjmuje Route (z MapPoint)
        val routeOverlayView =
            findViewById<RouteOverlayView>(R.id.routeOverlayView) // Pobierz widok

        // 1. Obsługa przypadku null i brak PGW
        if (routeToDisplay == null || !worldFileLoaded) {
            if (routeToDisplay == null) Log.i(TAG, "Attempting to clear route display.")
            if (!worldFileLoaded) Log.e(
                TAG,
                "Cannot display route: World file parameters not loaded."
            )
            // Wyczyść przekazując puste listy ScreenPoint i MarkerState do RouteOverlayView
            routeOverlayView.setScreenRouteData(emptyList(), emptyList())
            if (!worldFileLoaded) Toast.makeText(
                this,
                "Błąd: Parametry mapy nie wczytane.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // 2. Blok kodu, który wykonuje faktyczne PRZELICZENIE i RYSOWANIE
        // Ten blok potrzebuje wymiarów widoku, dlatego jest w lamdzie/funkcji.
        val performConversionAndSet = {
            // Sprawdzamy, czy widok na pewno ma wymiary (powinno być OK, jeśli tu dotarliśmy po layoucie)
            // i czy wymiary bitmapy są znane

            Log.d(TAG, "--- Rozpoczęcie Konwersji dla trasy: ${routeToDisplay?.name} ---")

            // Loguj WSZYSTKIE PARAMETRY PGW używane w mapToPixel
            Log.d(
                TAG,
                "PGW Parametry: A=$paramA, B=$paramB, C=$paramC, D=$paramD, E=$paramE, F=$paramF"
            )

            // Loguj WYMIARY używane w convertToScreenCoordinates
            Log.d(
                TAG,
                "Wymiary używane w konwersji: Bitmapa (${this.bitmapWidth}x${this.bitmapHeight}), Widok (${routeOverlayView.width}x${routeOverlayView.height})"
            )

            if (routeOverlayView.width > 0 && routeOverlayView.height > 0 && bitmapWidth > 0 && bitmapHeight > 0) {
                // Te listy będą przechowywać ScreenPoint (piksele ekranu) i stany do przekazania do RouteOverlayView
                val screenPixelPoints = mutableListOf<ScreenPoint>()
                val markerStatesForDrawing = mutableListOf<MarkerState>()


                // --- ITERUJEMY PRZEZ MARKERY W PRZEKAZANYM OBIEKCIE ROUTE (z MapPoint(Double, Double)) ---
                for ((index, marker) in routeToDisplay.markers.withIndex()) {
                    // Pobieramy SUROWE współrzędne mapowe (Double) z MapPoint w Markeri
                    val mapPoint_double = marker.point // To jest MapPoint(Double, Double)
                    val mapX_double = mapPoint_double.x // Double
                    val mapY_double = mapPoint_double.y // Double

                    Log.d(
                        TAG,
                        "Konwersja Punktu: Surowe Double ($mapX_double, $mapY_double)"
                    ) // Debugowanie


                    // Etap 1: Surowe Double -> Piksele obrazu mapy PNG (ScreenPoint)
                    // mapToPixel przyjmuje Double i zwraca ScreenPoint(Float, Float) (piksele obrazu)
                    val imagePixelPoint_float = mapToPixel(mapX_double, mapY_double)

                    if (imagePixelPoint_float != null) {
                        Log.d(
                            TAG,
                            "  mapToPixel -> Piksele Obrazu PNG (${imagePixelPoint_float.x}, ${imagePixelPoint_float.y})"
                        ) // Debugowanie

                        // Etap 2: Piksele obrazu mapy PNG (ScreenPoint) -> Piksele ekranu (ScreenPoint)
                        // convertToScreenCoordinates przyjmuje Float, więc bierzemy x/y z ScreenPoint z mapToPixel
                        val screenPoint_float = convertToScreenCoordinates(
                            imagePixelPoint_float.x, // Wejście to ScreenPoint z mapToPixel, używamy jego Floatów
                            imagePixelPoint_float.y,
                            bitmapWidth = this.bitmapWidth, // Rzeczywiste wymiary bitmapy
                            bitmapHeight = this.bitmapHeight,
                            viewWidth = routeOverlayView.width, // Rzeczywiste wymiary widoku
                            viewHeight = routeOverlayView.height // Rzeczywiste wymiary widoku
                        )

                        Log.d(
                            TAG,
                            "  convertToScreenCoordinates -> Piksele Ekranu (${screenPoint_float.x}, ${screenPoint_float.y})"
                        ) // Debugowanie

                        val state = when {
                            index <= currentTargetIndex -> MarkerState.VISITED // Punkty przed celem są odwiedzone
                            index == currentTargetIndex + 1 -> MarkerState.CURRENT // Punkt celu jest aktualny
                            else -> MarkerState.PENDING // Punkty po celu oczekują
                        }
                        markerStatesForDrawing.add(state)
                        screenPixelPoints.add(screenPoint_float)

                        // --- Dodajemy ScreenPoint (piksele ekranu) i stan do list do rysowania ---
//                        screenPixelPoints.add(screenPoint_float) // Dodajemy ScreenPoint (piksele ekranu)
//                        markerStatesForDrawing.add(marker.state) // Dodajemy odpowiadający stan
                    } else {
                        Log.w(
                            TAG,
                            "Could not convert map point (${mapX_double}, ${mapY_double}) to image pixel (mapToPixel returned null)."
                        )
                    }
                }

                // --- PO PRZELICZENIU WSZYSTKICH PUNKTÓW ---
                if (screenPixelPoints.isNotEmpty()) {
                    // Przekazujemy gotowe listy ScreenPoint (piksele ekranu) i MarkerState do RouteOverlayView
                    routeOverlayView.setScreenRouteData(screenPixelPoints, markerStatesForDrawing)

                    Log.i(
                        TAG,
                        "Route '${routeToDisplay.name}' processed (${routeToDisplay.markers.size} MapPoints) and set for display (${screenPixelPoints.size} ScreenPoints)."
                    )
                } else {
                    Log.w(
                        TAG,
                        "No valid screen pixel points could be created for route '${routeToDisplay.name}'. Clearing display."
                    )
                    routeOverlayView.clearRoute() // Użyj metody do czyszczenia
                }

            } else {
                // Logujemy, jeśli blok performConversionAndSet został wywołany, ale wymiary były 0
                Log.e(
                    TAG,
                    "performRouteDisplay called but view or bitmap dimensions are not available! " +
                            "View: ${routeOverlayView.width}x${routeOverlayView.height}, " +
                            "Bitmap: ${bitmapWidth}x${bitmapHeight}"
                )
                routeOverlayView.clearRoute() // Wyczyść na wszelki wypadek
                Toast.makeText(this, "Błąd: Wymiary widoku mapy niedostępne.", Toast.LENGTH_SHORT)
                    .show()
            }
        }
        // --- KONIEC BLOKU performConversionAndSet ---


        // 3. Logika czekania na layout/wykonania od razu
        // Sprawdź, czy widok ma wymiary (jest gotowy po layoucie)
        if (routeOverlayView.width > 0 && routeOverlayView.height > 0) {
            // Jeśli widok ma już wymiary, od razu wykonaj przeliczenie i rysowanie
            Log.d(
                TAG,
                "View already laid out. Performing route display immediately for '${routeToDisplay.name}'."
            )
            performConversionAndSet()
        } else {
            // Jeśli widok nie ma jeszcze wymiarów, dodaj listenera, żeby poczekać na layout
            Log.d(
                TAG,
                "View not yet laid out. Adding OnGlobalLayoutListener for '${routeToDisplay.name}'."
            )
            routeOverlayView.viewTreeObserver.addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        // Usuwamy listenera od razu po pierwszym wywołaniu po layoucie
                        routeOverlayView.viewTreeObserver.removeOnGlobalLayoutListener(this)

                        // Teraz, gdy widok ma wymiary, wykonaj przeliczenie i rysowanie
                        Log.d(
                            TAG,
                            "Layout finished. Performing route display from listener for '${routeToDisplay.name}'."
                        )
                        performConversionAndSet()
                    }
                }
            )
        }
    }

    private fun convertToScreenCoordinates(
        bitmapX: Float,
        bitmapY: Float,
        bitmapWidth: Int,
        bitmapHeight: Int,
        viewWidth: Int,
        viewHeight: Int
    ): ScreenPoint {
        val scale = minOf(viewWidth.toFloat() / bitmapWidth, viewHeight.toFloat() / bitmapHeight)

        val dx = (viewWidth - bitmapWidth * scale) / 2f
        val dy = (viewHeight - bitmapHeight * scale) / 2f

        val screenX = bitmapX * scale + dx
        val screenY = bitmapY * scale + dy

        return ScreenPoint(screenX, screenY)
    }

    private fun loadWorldFileParameters(context: Context, filename: String) {
        Log.d(TAG, "Attempting to load world file: $filename")
        val assetManager = context.assets
        val params = mutableListOf<Double>()
        try {
            // Używamy 'use' aby zapewnić automatyczne zamknięcie strumieni
            assetManager.open(filename).use { inputStream ->
                InputStreamReader(inputStream).use { inputStreamReader ->
                    BufferedReader(inputStreamReader).use { reader ->
                        for (i in 1..6) {
                            val line = reader.readLine()
                            if (line != null) {
                                try {
                                    params.add(line.toDouble())
                                    Log.d(TAG, "Read line $i: $line")
                                } catch (e: NumberFormatException) {
                                    Log.e(
                                        TAG,
                                        "Error parsing line $i ('$line') to Double in $filename",
                                        e
                                    )
                                    worldFileLoaded = false
                                    return // Przerwij wczytywanie przy błędzie formatu
                                }
                            } else {
                                Log.e(
                                    TAG,
                                    "Error reading line $i from $filename: Unexpected end of file"
                                )
                                worldFileLoaded = false
                                return // Przerwij, jeśli plik jest za krótki
                            }
                        }
                    }
                }
            }

            if (params.size == 6) {
                paramA = params[0]
                paramD = params[1] // Uwaga na kolejność D i B w pliku!
                paramB = params[2]
                paramE = params[3]
                paramC = params[4]
                paramF = params[5]
                worldFileLoaded = true
                Log.i(TAG, "World file '$filename' loaded successfully.")
                Log.d(
                    TAG,
                    "Params: A=$paramA, B=$paramB, C=$paramC, D=$paramD, E=$paramE, F=$paramF"
                )

                // Sprawdzenie czy obraz nie jest obrócony (B i D bliskie zeru)
                if (kotlin.math.abs(paramB) > 1e-9 || kotlin.math.abs(paramD) > 1e-9) {
                    Log.w(
                        TAG,
                        "World file indicates image rotation (B or D is non-zero). Simple transformation formula might be inaccurate."
                    )
                }
                // Sprawdzenie czy E jest ujemne
                if (paramE >= 0) {
                    Log.w(
                        TAG,
                        "Parameter E (line 4) is non-negative ($paramE). This is unusual for north-up images. Pixel Y coordinate might be inverted."
                    )
                }

            } else {
                // To się nie powinno zdarzyć jeśli pętla przeszła 6 razy
                Log.e(
                    TAG,
                    "Error loading $filename: Incorrect number of parameters read (${params.size})"
                )
                worldFileLoaded = false
            }

        } catch (e: IOException) {
            Log.e(TAG, "Error reading world file '$filename' from assets", e)
            worldFileLoaded = false
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
            // Sprawdź, czy jesteśmy w stanie ROUTE_SELECTED
            if (currentAppState != AppState.ROUTE_SELECTED || this.selectedRoute == null || this.selectedRoute!!.markers.isEmpty()) {
                Log.w(
                    TAG,
                    "Przycisk Start kliknięty w nieoczekiwanym stanie (${currentAppState}) lub brak trasy."
                )
                resetAppState()
                return@setOnClickListener
            }

            val route = this.selectedRoute!!

            // --- Logika po kliknięciu "Start" (przy punkcie 0) ---
            // Użytkownik potwierdza dotarcie do punktu 0.
            this.currentPointIndex = 0 // Index POTWIERDZONEGO punktu

            // Ukryj przycisk Start
            startButton.visibility = View.GONE


            // Zmieniamy przyciski w zależności od tego, czy trasa ma tylko 1 punkt
            if (route.markers.size > 1) {
                // Trasa ma więcej niż 1 punkt, pokaż przycisk Dalej
                nextButton.visibility = View.VISIBLE
                stopButton.visibility = View.GONE
                currentAppState = AppState.STARTED // Stan: rozpoczęta, Dalej widoczny
                Log.d(
                    TAG,
                    "Stan aplikacji: ${currentAppState}. Rozpoczęto nawigację (punkt 0), pokaż Dalej."
                )
            } else {
                // Trasa ma tylko 1 punkt. Po Start od razu przechodzimy do stanu Gotowy do Stop.
                // Przycisk Stop jest od razu widoczny.
                nextButton.visibility = View.GONE
                stopButton.visibility = View.VISIBLE
                currentAppState = AppState.READY_FOR_STOP // Stan: gotowa do Stop (przy 1 punkcie)
            }

            // --- Zaktualizuj wyświetlanie trasy na overlay ---
            displayGeographicRoute(route, this.currentPointIndex)

            startLogging(route)
            val arrivedPoint = route.markers.getOrNull(this.currentPointIndex)?.point // Punkt 0
            logNavigationButtonClick("START", this.currentPointIndex, arrivedPoint)
        }


        nextButton.setOnClickListener {
            // Sprawdź, czy jesteśmy w stanie "W trakcie"
            if (currentAppState != AppState.STARTED || this.selectedRoute == null) {
                Log.w(TAG, "Przycisk Dalej kliknięty w nieoczekiwanym stanie lub brak trasy.")
                // Reset do stanu początkowego
                resetAppState()
                return@setOnClickListener
            }

            val route = this.selectedRoute!! // Mamy pewność, że trasa istnieje na podstawie stanu

            // --- Przejdź do następnego punktu ---
            this.currentPointIndex++ // Zwiększ index

            val targetPoint = route.markers.getOrNull(this.currentPointIndex)?.point
            logNavigationButtonClick("NEXT", this.currentPointIndex, targetPoint)
            // --- Zaktualizuj wyświetlanie trasy na overlay ---
            // Wywołaj displayGeographicRoute, żeby trasa wyświetliła się ze zaktualizowanymi stanami (poprzedni punkt VISITED, obecny CURRENT)
            displayGeographicRoute(route, this.currentPointIndex)

            // --- Sprawdź, czy dotarliśmy do ostatniego punktu ---
            if (this.currentPointIndex == route.markers.size - 2) {
                // Jesteśmy przy ostatnim punkcie
                // Ukryj przycisk Dalej, pokaż przycisk Stop
                nextButton.visibility = View.GONE
                stopButton.visibility = View.VISIBLE

                // Zaktualizuj stan aplikacji
                currentAppState = AppState.READY_FOR_STOP
            }
        }


        stopButton.setOnClickListener {
            // Sprawdź, czy jesteśmy w stanie READY_FOR_STOP
            // Przycisk Stop jest widoczny tylko w tym stanie.
            // currentPointIndex W TYM MOMENCIE wskazuje na index PRZEDOSTATNIEGO punktu (size - 2),
            // ponieważ ostatnie kliknięcie Dalej ustawiło go na size - 2 i zmieniło przycisk na Stop.
            if (currentAppState != AppState.READY_FOR_STOP || this.selectedRoute == null) {
                Log.w(
                    TAG,
                    "Przycisk Stop kliknięty w nieoczekiwanym stanie (${currentAppState}) lub brak trasy."
                )
                // W przypadku błędu, resetujemy do stanu początkowego
                resetAppState() // Wywołaj resetAppState() w przypadku NIEOCZEKIWANEGO STANU
                return@setOnClickListener
            }

            val route = this.selectedRoute!!

            // --- Logika po kliknięciu "Stop" (przy OSTATNIM punkcie) ---
            // Użytkownik potwierdza dotarcie do ostatniego punktu.
            // Zwiększ index ostatni raz, żeby currentPointIndex stał się indexem ostatniego punktu (size - 1).
            this.currentPointIndex++ // Zwiększ index POTWIERDZONEGO punktu ostatni raz

            // --- ZALOGUJ KLIKNIĘCIE STOP ---
            // Zaloguj zdarzenie "STOP", index OSTATNIEGO potwierdzonego punktu (currentPointIndex), i jego współrzędne
            val arrivedPoint =
                route.markers.getOrNull(this.currentPointIndex)?.point // Punkt currentPointIndex (index ostatniego punktu)
            logNavigationButtonClick("STOP", this.currentPointIndex, arrivedPoint)

            Log.i(TAG, "Nawigacja zakończona dla trasy: ${route.name}.")

            stopLogging()

            displayGeographicRoute(route, route.markers.size)

            Handler(Looper.getMainLooper()).postDelayed({
                Log.d(TAG, "Opóźnione wywołanie resetAppState() po Stop.")
                resetAppState()
            }, 3000)
        }
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
        this.currentPointIndex = -1

        // Ustaw przyciski do stanu początkowego
        startButton.visibility = View.VISIBLE
        startButton.isEnabled = false
        nextButton.visibility = View.GONE
        stopButton.visibility = View.GONE

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
        // Sprawdź uprawnienia i stan WiFi
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_WIFI_STATE
            ) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CHANGE_WIFI_STATE
            ) != PackageManager.PERMISSION_GRANTED || // Potrzebne do startScan
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "WiFi: Próba skanowania bez wystarczających uprawnień.")
            stopLoggingInternal(saveData = false)
            return false
        }
        if (!wifiManager.isWifiEnabled) {
            Log.w(TAG, "WiFi: Próba skanowania przy wyłączonym WiFi.")
            Toast.makeText(this, "WiFi wyłączone, skanowanie WiFi pominięte.", Toast.LENGTH_SHORT)
                .show()
            return false
        }

        return try {
            Log.d(TAG, "WiFi: Wywołanie wifiManager.startScan()")
            val started = wifiManager.startScan()
            if (!started) {
                Log.w(
                    TAG,
                    "WiFi: wifiManager.startScan() zwrócił false. Skanowanie nie zostało zainicjowane (możliwe throttlowanie)."
                )
            }
            started
        } catch (se: SecurityException) {
            Log.e(TAG, "WiFi: SecurityException podczas startScan.", se)
            stopLoggingInternal(saveData = false)
            false
        } catch (e: Exception) {
            Log.e(TAG, "WiFi: Wyjątek podczas startScan.", e)
            false
        }
    }

    private fun processWifiScanResults() {
        // Sprawdź uprawnienie przed dostępem do wyników
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_WIFI_STATE
            ) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "WiFi: Brak uprawnień do pobrania wyników skanowania.")
            stopLoggingInternal(saveData = false)
            return
        }

        try {
            val scanResults = wifiManager.scanResults
            Log.d(TAG, "WiFi: Przetwarzanie ${scanResults?.size ?: 0} wyników.")

            if (scanResults == null || scanResults.isEmpty()) {
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
                startButton.isEnabled = true

                currentAppState = AppState.ROUTE_SELECTED
                Log.d(
                    TAG,
                    "Stan aplikacji: ${currentAppState}. Wybrano trasę: ${selectedRouteFromDialog.name}"
                )

                this.currentPointIndex = -1
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


        // Wyczyść wyświetlanie trasy na overlay podczas tworzenia nowej trasy
        routeOverlayView.clearRoute()

        // Otwórz fragment do tworzenia trasy
        val fragment = CreateRouteFragment()
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
