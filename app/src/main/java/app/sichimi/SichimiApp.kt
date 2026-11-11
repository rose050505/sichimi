package app.sichimi

import android.app.Application
import app.sichimi.core.Graph

class SichimiApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this).also { it.start() }
    }
}
