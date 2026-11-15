package dev.pleos3.rental

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.pleos3.car.DebugBridge
import dev.pleos3.car.PleosCarSignals
import dev.pleos3.car.PleosLocationFeed
import dev.pleos3.car.PleosNaviGate
import dev.pleos3.car.PleosSpeechInput
import dev.pleos3.car.PleosVoice
import dev.pleos3.rental.ui.CarScale
import dev.pleos3.rental.ui.RentalApp

class MainActivity : ComponentActivity() {
    private lateinit var controller: RentalController
    private lateinit var voice: PleosVoice
    private lateinit var speech: PleosSpeechInput

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        DebugBridge.install(applicationContext)
        val signals = PleosCarSignals(applicationContext)
        voice = PleosVoice(applicationContext)
        val navi = PleosNaviGate(applicationContext)
        speech = PleosSpeechInput(applicationContext)
        val locations = PleosLocationFeed(applicationContext)

        controller = RentalController(
            signals = signals,
            voice = voice,
            navi = navi,
            speech = speech,
            locations = locations,
        )
        controller.start()

        setContent {
            CarScale {
                val state by controller.state.collectAsState()
                RentalApp(state = state, onAction = controller::tap)
            }
        }
    }

    override fun onDestroy() {
        if (::controller.isInitialized) controller.stop()
        if (::speech.isInitialized) speech.stop()
        if (::voice.isInitialized) voice.stop()
        super.onDestroy()
    }
}
