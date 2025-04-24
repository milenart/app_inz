BLE:
    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) -> ta linia sprawia, że skanowanie jest praktycznie ciągłe
    oznacza to, że opóźnienia praktycznie nie są kwestią telefonu. Urządzenia BLE są w stanie wysyłać reklamy co jakiś
    określony cykl. Nierówność interwału między pomiarami wynika właśnie z tego. Dodatkowym drugorzędnym czynnikiem 
    jest cykliczność skanowania. Dodatkowo telefon potrzebuje czasu na przetworzenie wyników. 

SENSORY:
    SENSOR_DELAY_FASTEST -> generalnie skanują się co 2-3ms chyba zdecydowanie za często, magnetometr jakies 12ms
    SENSOR_DELAY_GAME -> tutaj acc i żyro 3-4ms, magne jakies 15ms
    SENSOR_DELAY_UI -> 40-60ms, wszystkie sensory
    SENSOR_DELAY_NORMAL -> jakies 200ms, wszystkie sensory

WiFi:
    niewiadomo czemu nagle skany sa robione co jakies 3-4 sekundy. 
TODO:
    2. Częstsze skany wifi - Lipka
    3. dodanie logiki usowania trasy
    4. nie wiem czemu nie rysuje poprawnie trasy, skala cos sie zjebala
