package app.couch.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.couch.tv.ui.CouchRoot
import app.couch.tv.ui.CouchTheme

class MainActivity : ComponentActivity() {

    private val app: CouchApp get() = application as CouchApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CouchTheme {
                CouchRoot(app)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.inForeground = true
    }

    override fun onStop() {
        super.onStop()
        app.inForeground = false
    }
}
