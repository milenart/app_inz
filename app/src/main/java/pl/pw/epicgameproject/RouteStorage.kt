package pl.pw.epicgameproject

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileReader
import java.io.FileWriter

object RouteStorage {

    private const val FILE_NAME = "routes.json"
    private val gson = Gson()
    private const val TAG_STORAGE = "RouteStorage"

    // Wczytaj wszystkie trasy z pliku JSON
//    fun loadRoutes(context: Context): List<Route> {
//        val file = File(context.filesDir, FILE_NAME)
//
//        if (!file.exists()) return emptyList()
//
//        val json = file.readText()
//        val type = object : TypeToken<List<Route>>() {}.type
//
//        return gson.fromJson(json, type) ?: emptyList()
//    }

    // Zapisz listę tras do pliku JSON
    fun saveRoutes(context: Context, routes: List<Route>) {
        val file = File(context.filesDir, FILE_NAME)
        val json = gson.toJson(routes)
        file.writeText(json)
    }

    // Dodaj nową trasę i zapisz do pliku
//    fun addRoute(context: Context, newRoute: Route) {
//        val routes = loadRoutes(context).toMutableList()
//        routes.add(newRoute)
//        saveRoutes(context, routes)
//    }
    fun addRoute(context: Context, route: Route) {
        val file = File(context.getExternalFilesDir(null), FILE_NAME)

        Log.d(TAG_STORAGE, "Próba dodania trasy: '${route.name}'. Ścieżka pliku: ${file.absolutePath}")

        if (!file.exists()) {
            try {
                file.createNewFile()
                Log.d(TAG_STORAGE, "Plik tras (${FILE_NAME}) nie istniał, próba stworzenia.")
            } catch (e: Exception) {
                Log.e(TAG_STORAGE, "Błąd podczas tworzenia pliku tras: ${FILE_NAME}", e)
                return
            }
        } else {
            // Sprawdź, czy plik nie jest katalogiem, jeśli istnieje
            if (!file.isFile) {
                Log.e(TAG_STORAGE, "Ścieżka pliku tras (${FILE_NAME}) wskazuje na katalog, nie plik!")
                // Możesz usunąć katalog i spróbować ponownie lub zasygnalizować błąd
                // file.delete() // Opcjonalnie, usuń katalog jeśli napotkasz taki problem
                return
            }
        }


        try {
            val gson = Gson()
            val type = object : TypeToken<List<Route>>() {}.type

            val existingRoutes: MutableList<Route> = if (file.exists() && file.length() > 0) {
                try {
                    // Upewnij się, że plik nie jest pusty przed próbą czytania
                    FileReader(file).use { reader -> // Użyj use do FileReader też
                        gson.fromJson(reader, type) ?: mutableListOf()
                    }
                } catch (e: Exception) {
                    Log.e(TAG_STORAGE, "Błąd podczas wczytywania istniejących tras z pliku.", e)
                    mutableListOf() // Zresetuj listę w przypadku błędu wczytywania
                }
            } else {
                mutableListOf() // Plik pusty lub nie istniał
            }


            Log.d(TAG_STORAGE, "Wczytano ${existingRoutes.size} tras przed dodaniem nowej.")

            // ... (Sprawdzenie i usunięcie istniejącej trasy o tej samej nazwie - opcjonalne) ...
            val existingRouteWithSameName = existingRoutes.find { it.name == route.name }
            if (existingRouteWithSameName != null) {
                existingRoutes.remove(existingRouteWithSameName)
                Log.d(TAG_STORAGE, "Usunięto istniejącą trasę o tej samej nazwie '${route.name}' przed dodaniem nowej.")
            }


            existingRoutes.add(route)
            Log.d(TAG_STORAGE, "Dodano nową trasę '${route.name}' do listy w pamięci. Lista ma teraz ${existingRoutes.size} tras.")


            // --- Zapisz do pliku - DODAJ JAWNE flush() i close() ---
            FileWriter(file, false).use { writer -> // false = overwrite mode
                gson.toJson(existingRoutes, writer)
                writer.flush() // <--- WYMUŚ ZAPIS DO PLIKU
                // writer.close() // <--- BLOK use() WYWOŁA close() AUTOMATYCZNIE, NIE POTRZEBA TEGO JAWNIE
            }
            Log.i(TAG_STORAGE, "Zapisano całą listę tras do pliku (${FILE_NAME}).")

            // --- DODATKOWA WERYFIKACJA PO ZAPISIE (OPCJONALNE, DO DEBUGOWANIA) ---
            // Sprawdź, czy plik istnieje i ma niezerowy rozmiar zaraz po zapisie
            if (file.exists() && file.length() > 0) {
                Log.d(TAG_STORAGE, "Weryfikacja po zapisie: Plik istnieje i ma rozmiar ${file.length()} bajtów.")
            } else {
                Log.e(TAG_STORAGE, "Weryfikacja po zapisie: Plik nie istnieje lub jest pusty po próbie zapisu!")
                // Tutaj możesz podnieść flagę błędu lub rzucić wyjątek
            }


        } catch (e: Exception) {
            Log.e(TAG_STORAGE, "Błąd podczas dodawania/zapisywania trasy '${route.name}' do pliku", e)
            Toast.makeText(context, "Błąd zapisu trasy!", Toast.LENGTH_SHORT).show()
        }
    }

    fun deleteRoute(context: Context, routeName: String) {
        val file = File(context.getExternalFilesDir(null), FILE_NAME)

        Log.d(TAG_STORAGE, "Próba usunięcia trasy: '$routeName'. Ścieżka pliku: ${file.absolutePath}")

        if (!file.exists()) {
            Log.w(TAG_STORAGE, "Plik tras (${FILE_NAME}) nie istnieje. Nie można usunąć trasy: $routeName")
            // Trasa nie istnieje, więc "usunięta"
            return
        }

        try {
            val gson = Gson()
            val type = object : TypeToken<List<Route>>() {}.type
            val existingRoutes: MutableList<Route> = gson.fromJson(FileReader(file), type) ?: mutableListOf()

            Log.d(TAG_STORAGE, "Wczytano ${existingRoutes.size} tras przed próbą usunięcia.")

            // Znajdź trasę do usunięcia po nazwie
            val routeToRemove = existingRoutes.find { it.name == routeName }

            if (routeToRemove != null) {
                Log.d(TAG_STORAGE, "Znaleziono trasę do usunięcia: '${routeName}'.")
                existingRoutes.remove(routeToRemove)
                Log.d(TAG_STORAGE, "Usunięto trasę z listy w pamięci. Pozostało ${existingRoutes.size} tras.")

                // Zapisz zmodyfikowaną listę z powrotem do pliku
                FileWriter(file, false).use { writer -> // false = overwrite mode
                    gson.toJson(existingRoutes, writer)
                }
                Log.i(TAG_STORAGE, "Zapisano zmodyfikowaną listę tras do pliku (${FILE_NAME}).")

                // Sprawdź czy plik nadal istnieje i czy ma nową zawartość (opcjonalne, bardziej zaawansowane)
                // Na razie wystarczy logowanie sukcesu zapisu.


            } else {
                Log.w(TAG_STORAGE, "Trasa '${routeName}' nie znaleziona w wczytanych trasach. Nic do usunięcia.")
            }

        } catch (e: Exception) {
            Log.e(TAG_STORAGE, "Błąd podczas usuwania trasy '${routeName}' z pliku", e)
            // Nadal rzucaj wyjątek, żeby funkcja wywołująca (handleDeleteRoute) wiedziała, że był błąd
            throw e
        }
    }

    fun loadRoutes(context: Context): List<Route> {
        val file = File(context.getExternalFilesDir(null), FILE_NAME) // Ścieżka do pliku tras

        Log.d(TAG_STORAGE, "Próba wczytania tras ze ścieżki: ${file.absolutePath}")

        // Jeśli plik nie istnieje, zwróć pustą listę
        if (!file.exists()) {
            Log.w(TAG_STORAGE, "Plik tras (${FILE_NAME}) nie istnieje. Zwracam pustą listę.")
            return emptyList()
        }

        // Jeśli plik istnieje, ale jest pusty, zwróć pustą listę
        if (file.length() == 0L) { // Użyj 0L dla porównania z Long (rozmiar pliku)
            Log.w(TAG_STORAGE, "Plik tras (${FILE_NAME}) jest pusty. Zwracam pustą listę.")
            return emptyList()
        }

        // Jeśli plik istnieje i nie jest pusty, próbuj wczytać
        try {
            val gson = Gson()
            val type = object : TypeToken<List<Route>>() {}.type // Określa typ dla Gson

            // Użyj FileReader do odczytu pliku i bloku use do automatycznego zamknięcia
            FileReader(file).use { reader ->
                val routesList: List<Route>? = gson.fromJson(reader, type)
                val resultList = routesList ?: emptyList() // Jeśli Gson zwróci null, użyj pustej listy

                Log.i(TAG_STORAGE, "Wczytano ${resultList.size} tras z pliku (${FILE_NAME}).")
                return resultList // Zwróć wczytaną listę (lub pustą jeśli null)
            }

        } catch (e: Exception) {
            Log.e(TAG_STORAGE, "Błąd podczas wczytywania tras z pliku (${FILE_NAME})", e)
            // W przypadku błędu wczytywania (np. plik uszkodzony), zwróć pustą listę
            return emptyList()
        }
    }
}
