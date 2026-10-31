package app.ultimate.footprint

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Health Connect shows this when it asks why Ultimate wants health data. */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Column(Modifier.padding(24.dp)) {
                    Text("Why Ultimate asks", style = MaterialTheme.typography.headlineSmall)
                    Text("Footprint reads your steps, sleep, heart rate and distance from Health Connect to show you what your phone has written down about your body. It only reads, only when you tap the button, shows it on this screen, and sends it nowhere. Remove access any time in Health Connect.", Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}
