package pl.pw.epicgameproject

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException

private val TAG_STORAGE = "RouteStorage"
object RouteStorage {

    private const val FILE_NAME = "routes.json"
    private val gson = Gson()

    fun addRoute(context: Context, route: Route) {
        Log.d(TAG_STORAGE, "Attempting to save route: ${route.name}")
        val file = File(context.filesDir, FILE_NAME)
        val currentRoutes = loadRoutes(context).toMutableList()

        // Sprawdź, czy trasa o tej nazwie już istnieje. Jeśli tak, zastąp ją.
        val existingIndex = currentRoutes.indexOfFirst { it.name == route.name }
        if (existingIndex != -1) {
            currentRoutes[existingIndex] = route
            Log.d(TAG_STORAGE, "Route '${route.name}' already exists. Replacing it.")
        } else {
            currentRoutes.add(route)
            Log.d(TAG_STORAGE, "Route '${route.name}' is new. Adding it.")
        }

        try {
            FileWriter(file).use { writer ->
                // Gson automatycznie obsłuży pole 'floor' w MapPoint
                gson.toJson(currentRoutes, writer)
                Log.i(TAG_STORAGE, "Route '${route.name}' saved successfully to ${file.absolutePath}")
            }
        } catch (e: IOException) {
            Log.e(TAG_STORAGE, "Error saving route '${route.name}' to file.", e)
            throw e // Rzuć wyjątek, żeby MainActivity mogła go obsłużyć
        } catch (e: Exception) {
            Log.e(TAG_STORAGE, "Unexpected error saving route '${route.name}'.", e)
            throw e
        }
    }

    fun loadRoutes(context: Context): List<Route> {
        Log.d(TAG_STORAGE, "Attempting to load routes from file: $FILE_NAME")
        val file = File(context.filesDir, FILE_NAME)

        if (!file.exists()) {
            Log.i(TAG_STORAGE, "Route file '$FILE_NAME' does not exist. Returning empty list.")
            return emptyList()
        }

        try {
            FileReader(file).use { reader ->
                // Gson automatycznie obsłuży pole 'floor' w MapPoint
                val type = object : TypeToken<List<Route>>() {}.type
                val routes: List<Route>? = gson.fromJson(reader, type)
                val loadedCount = routes?.size ?: 0
                Log.i(TAG_STORAGE, "Successfully loaded $loadedCount routes from ${file.absolutePath}")
                return routes ?: emptyList()
            }
        } catch (e: IOException) {
            Log.e(TAG_STORAGE, "Error loading routes from file.", e)
            // Możesz zwrócić pustą listę lub rzucić wyjątek w zależności od potrzeb
            return emptyList()
        } catch (e: Exception) {
            Log.e(TAG_STORAGE, "Unexpected error loading routes.", e)
            // Możesz zwrócić pustą listę lub rzucić wyjątek
            return emptyList()
        }
    }

    fun deleteRoute(context: Context, routeName: String): Boolean {
        Log.d(TAG_STORAGE, "Attempting to delete route: $routeName")
        val file = File(context.filesDir, FILE_NAME)
        val currentRoutes = loadRoutes(context).toMutableList()

        val removed = currentRoutes.removeIf { it.name == routeName } // Usuń trasę o podanej nazwie

        if (removed) {
            try {
                // Zapisz zaktualizowaną listę tras
                FileWriter(file).use { writer ->
                    gson.toJson(currentRoutes, writer)
                    Log.i(TAG_STORAGE, "Route '$routeName' deleted and file updated successfully.")
                }
            } catch (e: IOException) {
                Log.e(TAG_STORAGE, "Error saving routes after deleting '$routeName'.", e)
                return false // Zwróć false, jeśli błąd zapisu
            }
            Log.d(TAG_STORAGE, "Route '$routeName' removed from list. Number of routes left: ${currentRoutes.size}")
            return true // Zwróć true, jeśli usunięto
        } else {
            Log.w(TAG_STORAGE, "Route '$routeName' not found for deletion.")
            return false // Zwróć false, jeśli trasy nie znaleziono
        }
    }
}