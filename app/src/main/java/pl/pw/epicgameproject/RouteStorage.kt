package pl.pw.epicgameproject

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

object RouteStorage {

    private const val FILE_NAME = "routes.json"
    private val gson = Gson()

    // Wczytaj wszystkie trasy z pliku JSON
    fun loadRoutes(context: Context): List<Route> {
        val file = File(context.filesDir, FILE_NAME)

        if (!file.exists()) return emptyList()

        val json = file.readText()
        val type = object : TypeToken<List<Route>>() {}.type

        return gson.fromJson(json, type) ?: emptyList()
    }

    // Zapisz listę tras do pliku JSON
    fun saveRoutes(context: Context, routes: List<Route>) {
        val file = File(context.filesDir, FILE_NAME)
        val json = gson.toJson(routes)
        file.writeText(json)
    }

    // Dodaj nową trasę i zapisz do pliku
     fun addRoute(context: Context, newRoute: Route) {
        val routes = loadRoutes(context).toMutableList()
        routes.add(newRoute)
        saveRoutes(context, routes)
    }

}
