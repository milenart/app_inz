package pl.pw.epicgameproject

// Importy Android
import android.Manifest
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
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt


class MainActivity : AppCompatActivity(), SensorEventListener {

    // --- Widoki ---
    private lateinit var map: MapView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    // --- Managery Systemowe ---
    private lateinit var wifiManager: WifiManager
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null

    // --- OSMDroid ---
    private lateinit var locationOverlay: MyLocationNewOverlay

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

        // Konfiguracja OSM
        Configuration.getInstance().load(this, getSharedPreferences("osm_prefs", Context.MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        // Inicjalizacja Managerów
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager // Inicjalizacja SensorManager

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

        if (accelerometerSensor == null) Log.w(TAG, "Sensor przyspieszenia (ACCELEROMETER) nie znaleziony!")
        if (gyroscopeSensor == null) Log.w(TAG, "Sensor żyroskopu (GYROSCOPE) nie znaleziony!")
        if (magnetometerSensor == null) Log.w(TAG, "Sensor pola magnetycznego (MAGNETIC_FIELD) nie znaleziony!")
        if (barometerSensor == null) Log.w(TAG, "Sensor ciśnienia (PRESSURE) nie znaleziony!")


        // Znalezienie Widoków
        map = findViewById(R.id.map)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        // Ustawienie Komponentów UI
        setupMap()
        setupButtons()

        // Rejestracja Odbiornika WiFi
        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, intentFilter)

        // Sprawdzenie i Prośba o Uprawnienia
        if (!hasRequiredPermissions()) {
            requestMissingPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called")
        map.onResume()
        // Jeśli logowanie było aktywne, można rozważyć jego wznowienie,
        // ale obecna logika zatrzymuje je w onPause, co jest bezpieczniejsze bez usługi pierwszoplanowej.
        // Jeśli sensory były zarejestrowane (isLogging = true), zarejestruj je ponownie.
        if (isLogging) {
            registerSensors() // Rejestrujemy sensory ponownie, jeśli logowanie było aktywne
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause called")
        map.onPause()
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
    }

    // --- Konfiguracja UI ---
    private fun setupMap() { /* Bez zmian - kod taki jak w oryginale */
        Log.d(TAG, "Setting up map")
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(18.0)

        // Add location overlay
        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        locationOverlay.enableMyLocation() // Tries to get location updates
        locationOverlay.enableFollowLocation() // Follow user location
        map.overlays.add(locationOverlay)

        // Set initial center (e.g., Warsaw or last known location if available)
        val startPoint = GeoPoint(52.2297, 21.0122) // Default to Warsaw center
        map.controller.setCenter(startPoint)

        // Attempt to center on current location once available
        locationOverlay.runOnFirstFix {
            runOnUiThread {
                map.controller.animateTo(locationOverlay.myLocation)
                Log.d(TAG, "Map centered on first location fix.")
            }
        }
    }

    private fun setupButtons() { /* Lekka modyfikacja logiki */
        Log.d(TAG, "Setting up buttons")
        startButton.setOnClickListener {
            Log.d(TAG, "Start button clicked")
            if (hasRequiredPermissions()) {
                startLogging()
            } else {
                Log.w(TAG, "Start clicked but permissions missing, requesting.")
                requestMissingPermissions()
            }
        }

        stopButton.setOnClickListener {
            Log.d(TAG, "Stop button clicked")
            stopLogging() // Wywołuje zapis danych
        }
        // Stan początkowy
        stopButton.isEnabled = false
        startButton.isEnabled = hasRequiredPermissions() // Włączony tylko jeśli są uprawnienia
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            val granted = hasRequiredPermissions()
            startButton.isEnabled = granted // Aktualizuj stan przycisku START
            if (granted) {
                Log.i(TAG, "All required permissions granted after request.")
                Toast.makeText(this, "Uprawnienia przyznane.", Toast.LENGTH_SHORT).show()
                // Sprawdź ponownie stan Bluetooth po przyznaniu uprawnień
                if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
                    Toast.makeText(this, "Pamiętaj, aby włączyć Bluetooth.", Toast.LENGTH_LONG).show()
                } else if (bluetoothLeScanner == null) {
                    bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner // Spróbuj ponownie zainicjować skaner
                }
            } else {
                Log.w(TAG, "Not all required permissions were granted.")
                Toast.makeText(this, "Nie przyznano wszystkich wymaganych uprawnień. Funkcjonalność ograniczona.", Toast.LENGTH_LONG).show()
            }
        }
    }


    // --- Kontrola Logowania ---

    private fun startLogging() {
        if (isLogging) {
            Log.w(TAG, "Logowanie jest już aktywne.")
            return
        }
        // Sprawdź ponownie uprawnienia
        if (!hasRequiredPermissions()) {
            Log.e(TAG, "Nie można rozpocząć logowania - brak uprawnień.")
            Toast.makeText(this, "Brak uprawnień do rozpoczęcia logowania.", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Włącz Bluetooth, aby skanować urządzenia BLE.", Toast.LENGTH_SHORT).show()
            // Można zdecydować czy kontynuować tylko z WiFi/krokami, czy zatrzymać
            return // Zablokuj start, jeśli BLE jest wymagane
        }
        if (bluetoothLeScanner == null) {
            bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner // Spróbuj ponownie zainicjować
            if (bluetoothLeScanner == null) {
                Log.e(TAG, "Nie można uzyskać BluetoothLeScanner.")
                Toast.makeText(this, "Nie można zainicjować skanera BLE.", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // Sprawdź sensory
        if (accelerometerSensor  == null) {
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
        val timestampFolder = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val folderName = "log_$timestampFolder"
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

    // Funkcja pomocnicza do zatrzymywania wewnętrznych procesów logowania
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
                    Toast.makeText(this, "Błąd zapisu danych WiFi: ${e.message}", Toast.LENGTH_LONG).show()
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
                    Toast.makeText(this, "Błąd zapisu danych BLE: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } else {
                Log.i(TAG, "Brak danych BLE do zapisania.")
            }

            if (accelerometerData.size > 1) {
                try {
                    writeCsvData(this, accelerometerData, "accelerometer_log", targetPath)
                } catch (e: IOException) {
                    Log.e(TAG, "Błąd zapisu pliku CSV dla akcelerometru", e)
                    Toast.makeText(this, "Błąd zapisu danych akcelerometru: ${e.message}", Toast.LENGTH_LONG).show()
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
                    Toast.makeText(this, "Błąd zapisu danych żyroskopu: ${e.message}", Toast.LENGTH_LONG).show()
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
                    Toast.makeText(this, "Błąd zapisu danych magnetometru: ${e.message}", Toast.LENGTH_LONG).show()
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
                    Toast.makeText(this, "Błąd zapisu danych barometru: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } else {
                Log.i(TAG, "Brak danych z barometru do zapisania.")
            }

            if (allData.size > 1) {
                try {
                    writeCsvData(this, allData, "allData", targetPath)
                } catch (e: IOException) {
                    Log.e(TAG, "Błąd zapisu pliku CSV dla allData", e)
                    Toast.makeText(this, "Błąd zapisu danych allData: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } else {
                Log.i(TAG, "Brak danych z allData do zapisania.")
            }


            Toast.makeText(this, "Zatrzymano logowanie. Dane zapisane (jeśli zebrano).", Toast.LENGTH_SHORT).show()
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
        Log.i(TAG,"Wywołano stopLogging (z zapisem).")
        stopLoggingInternal(saveData = true) // Zatrzymaj i zapisz dane
    }


    // --- Pobieranie Lokalizacji ---
    /**
     * Pobiera aktualną najlepszą dostępną lokalizację precyzyjną i przybliżoną.
     * Zwraca parę: Pair(Pair(latFine, lonFine), Pair(latCoarse, lonCoarse))
     * Wartości będą "0.0" jeśli lokalizacja nie jest dostępna lub brak uprawnień.
     */
    private fun getCurrentLocationData(): Pair<Pair<String, String>, Pair<String, String>> {
        var latFine = "0.0"
        var lonFine = "0.0"
        var latCoarse = "0.0"
        var lonCoarse = "0.0"

        // Lokalizacja Precyzyjna (z mapy)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationOverlay.myLocation?.let {
                latFine = it.latitude.toString()
                lonFine = it.longitude.toString()
            } ?: Log.w(TAG, "Lokalizacja precyzyjna: brak fixa (locationOverlay.myLocation is null).")
        } else {
            Log.w(TAG, "Lokalizacja precyzyjna: brak uprawnień.")
        }

        // Lokalizacja Przybliżona (z LocationManager)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                val coarseLoc: Location? = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) // Fallback na GPS
                    ?: locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) // Ostateczny fallback

                coarseLoc?.let {
                    latCoarse = it.latitude.toString()
                    lonCoarse = it.longitude.toString()
                } ?: Log.w(TAG, "Lokalizacja przybliżona: brak fixa (getLastKnownLocation zwrócił null).")
            } catch (se: SecurityException) {
                Log.e(TAG, "Lokalizacja przybliżona: SecurityException.", se)
                // To nie powinno się zdarzyć, jeśli checkSelfPermission przeszedł
            }
        } else {
            Log.w(TAG, "Lokalizacja przybliżona: brak uprawnień.")
        }

        return Pair(Pair(latFine, lonFine), Pair(latCoarse, lonCoarse))
    }


    // --- Obsługa Skanowania WiFi ---
    private fun triggerWifiScan(): Boolean {
        // Sprawdź uprawnienia i stan WiFi
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CHANGE_WIFI_STATE) != PackageManager.PERMISSION_GRANTED || // Potrzebne do startScan
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "WiFi: Próba skanowania bez wystarczających uprawnień.")
            stopLoggingInternal(saveData = false)
            return false
        }
        if (!wifiManager.isWifiEnabled) {
            Log.w(TAG, "WiFi: Próba skanowania przy wyłączonym WiFi.")
            Toast.makeText(this, "WiFi wyłączone, skanowanie WiFi pominięte.", Toast.LENGTH_SHORT).show()
            return false
        }

        return try {
            Log.d(TAG, "WiFi: Wywołanie wifiManager.startScan()")
            val started = wifiManager.startScan()
            if (!started) {
                Log.w(TAG, "WiFi: wifiManager.startScan() zwrócił false. Skanowanie nie zostało zainicjowane (możliwe throttlowanie).")
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
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
            val (fineLoc, coarseLoc) = getCurrentLocationData()

            scanResults.forEach { result ->
                if (!result.BSSID.isNullOrEmpty()) {
                    val data = arrayOf(
                        timestamp,
                        "WiFi",
                        result.BSSID,
                        result.SSID ?: "<Brak SSID>",
                        result.level.toString(),
                        result.frequency.toString(),
                        //fineLoc.first, fineLoc.second,
                        //coarseLoc.first, coarseLoc.second,
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
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "BLE: Brak uprawnienia BLUETOOTH_SCAN.")
                Toast.makeText(this,"Brak uprawnienia do skanowania BLE.",Toast.LENGTH_SHORT).show()
                stopLoggingInternal(false) // Zatrzymaj, bo kluczowe uprawnienie brakuje
                return
            }
        } else {
            // Starsze wersje wymagają BLUETOOTH_ADMIN i lokalizacji (już sprawdzane w startLogging)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "BLE: Brak uprawnienia BLUETOOTH_ADMIN.")
                Toast.makeText(this,"Brak uprawnienia admina Bluetooth.",Toast.LENGTH_SHORT).show()
                stopLoggingInternal(false)
                return
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "BLE: Brak uprawnienia ACCESS_FINE_LOCATION - skanowanie może nie działać poprawnie.")
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
            Toast.makeText(this, "Błąd uprawnień przy starcie skanowania BLE.", Toast.LENGTH_SHORT).show()
            stopLoggingInternal(false)
        } catch (e: Exception) {
            Log.e(TAG, "BLE: Wyjątek podczas startScan.", e)
            Toast.makeText(this, "Nieoczekiwany błąd przy starcie skanowania BLE.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopBleScan() {
        // Sprawdź uprawnienia przed próbą zatrzymania
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "BLE: Brak uprawnienia BLUETOOTH_SCAN do zatrzymania skanowania (ale próbuję).")
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
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "BLE: Brak uprawnienia BLUETOOTH_CONNECT, nie można pobrać nazwy urządzenia ${result.device.address}.")
                deviceName = "<Brak uprawnień>"
            }
        }
        // Na starszych API lub jeśli mamy uprawnienie CONNECT:
        if (deviceName == "<Brak nazwy>" || deviceName == "<Brak uprawnień>") {
            try {
                // Sprawdzenie uprawnienia BLUETOOTH (ogólne) lub CONNECT (API 31+) jest niejawnie wymagane przez getName()
                deviceName = result.device.name ?: "<Brak nazwy>"
            } catch (se: SecurityException) {
                Log.w(TAG, "BLE: SecurityException przy próbie pobrania nazwy dla ${result.device.address}.", se)
                deviceName = "<Brak uprawnień>"
            }
        }


        val deviceAddress = result.device.address ?: "<Brak adresu>"
        val rssi = result.rssi.toString()
        val timestamp = SimpleDateFormat("MMddHHmmss.SSS", Locale.getDefault()).format(Date())
        val (fineLoc, coarseLoc) = getCurrentLocationData()

        Log.v(TAG, "BLE: Znaleziono urządzenie: Adres=${deviceAddress}, Nazwa=${deviceName}, RSSI=${rssi}") // Użyj Verbose dla częstych logów

        val data = arrayOf(
            timestamp,
            "BLE",
            deviceName,
            deviceAddress,
            rssi,
            //fineLoc.first, fineLoc.second,
            //coarseLoc.first, coarseLoc.second,
        )
        bleData.add(data)
        allData.add(data)
    }


    // --- Obsługa Sensorów (Kroki i Azymut) ---
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
        val (fineLoc, coarseLoc) = getCurrentLocationData()

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
                    z.toString(),
//                    fineLoc.first, fineLoc.second,
//                    coarseLoc.first, coarseLoc.second,
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
                    z.toString(),
//                    fineLoc.first, fineLoc.second,
//                    coarseLoc.first, coarseLoc.second,
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
//                    fineLoc.first, fineLoc.second,
//                    coarseLoc.first, coarseLoc.second,
                )
                magnetometerData.add(data)
                allData.add(data)
            }
            Sensor.TYPE_PRESSURE -> {
                val pressure = event.values[0]
                //Log.v(TAG, "Barometr: Ciśnienie=$pressure")
                val data = arrayOf(
                    timestamp,
                    pressure.toString(),
//                    fineLoc.first, fineLoc.second,
//                    coarseLoc.first, coarseLoc.second,
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
// Dodajemy nowy parametr: relativeDirectoryPath
    @Throws(IOException::class)
    private fun writeCsvData(context: Context, dataList: List<Array<String>>, filePrefix: String, relativeDirectoryPath: String) {
        if (dataList.size <= 1) {
            Log.i(TAG, "Brak danych do zapisania dla prefiksu: $filePrefix.")
            return
        }

        // Sprawdzamy, czy ścieżka nie jest pusta (choć powinna być ustawiona w startLogging)
        if (relativeDirectoryPath.isBlank()) {
            Log.e(TAG, "Ścieżka względna do zapisu jest pusta! Zapisuję bezpośrednio w Downloads.")
            // Można tu albo przerwać, albo zapisać domyślnie w Downloads
            // W tym przykładzie zapiszemy w Downloads jako fallback
            writeCsvData(context, dataList, filePrefix, Environment.DIRECTORY_DOWNLOADS) // Wywołanie rekurencyjne z domyślną ścieżką
            return
        }


        Log.d(TAG, "Próba zapisu ${dataList.size} wierszy do CSV dla prefiksu: $filePrefix w folderze: $relativeDirectoryPath")
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
            Log.e(TAG, "Nie udało się utworzyć wpisu MediaStore dla $displayName w $relativeDirectoryPath.", e)
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
                    Log.i(TAG, "Pomyślnie zapisano ${dataList.size} wierszy do $displayName w $relativeDirectoryPath.")
                    success = true
                }
                /*
                //Informujemy użytkownika o pełnej ścieżce względnej
                Toast.makeText(context, "Plik $displayName zapisany w $relativeDirectoryPath.", Toast.LENGTH_LONG).show()
                */

            } ?: run {
                Log.e(TAG, "Nie udało się otworzyć strumienia wyjściowego dla URI: $uri")
                Toast.makeText(context, "Błąd zapisu pliku $displayName (nie można otworzyć strumienia).", Toast.LENGTH_LONG).show()
            }
        } catch (e: IOException) {
            // ... (obsługa błędów bez zmian) ...
            Log.e(TAG, "IOException podczas zapisu pliku CSV $displayName", e)
            Toast.makeText(context, "Błąd I/O podczas zapisu $displayName.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            // ... (obsługa błędów bez zmian) ...
            Log.e(TAG, "Nieoczekiwany wyjątek podczas zapisu pliku CSV $displayName", e)
            Toast.makeText(context, "Nieoczekiwany błąd podczas zapisu $displayName.", Toast.LENGTH_LONG).show()
        } finally {
            // ... (finalne operacje na MediaStore - bez zmian, logika zależy od 'success' i 'uri') ...
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, if (success) 0 else 1)
                try {
                    if (success) {
                        resolver.update(uri, contentValues, null, null)
                    } else {
                        Log.w(TAG, "Zapis nie powiódł się, usuwanie wpisu MediaStore dla $displayName.")
                        resolver.delete(uri, null, null)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Nie udało się zaktualizować/usunąć stanu IS_PENDING dla $displayName.", e)
                }
            } else if (!success && uri != null) {
                Log.w(TAG, "Zapis $displayName nie powiódł się na starszym API. Plik może pozostać niekompletny.")
            }
        }
    }


    // Funkcja udostępniania (jeśli potrzebna, można wywołać np. z nowego przycisku)
    // Obecnie nie jest używana, ale pozostawiona na wszelki wypadek.
    // Należałoby ją dostosować do wyboru, które dane udostępnić (WiFi, BLE, kroki?)
    private fun shareTextResults() {
        // ... implementacja udostępniania tekstu ...
        // Przykład dla danych WiFi:
        if (wifiData.size <= 1) {
            Toast.makeText(this, "Brak danych WiFi do udostępnienia.", Toast.LENGTH_SHORT).show()
            return
        }
        // ... reszta kodu z shareTextResults jak w oryginale, ale działająca na wifiData ...
        Log.w(TAG,"Funkcja shareTextResults nie została w pełni zaimplementowana dla wielu typów danych.")
    }
}