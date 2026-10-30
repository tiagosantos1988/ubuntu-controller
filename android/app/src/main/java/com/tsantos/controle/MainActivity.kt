package com.tsantos.controle

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val Fundo = Color(0xFF0B0F14)
val Superficie = Color(0xFF141B23)
val Superficie2 = Color(0xFF1D2733)
val Primaria = Color(0xFF4FC3F7)
val Texto = Color(0xFFE6EDF3)
val TextoFraco = Color(0xFF8B98A5)
val Alerta = Color(0xFFFF6B6B)
val Ok = Color(0xFF56D364)

private val Cores = darkColorScheme(
    primary = Primaria,
    onPrimary = Color(0xFF04121C),
    background = Fundo,
    onBackground = Texto,
    surface = Superficie,
    onSurface = Texto,
    surfaceVariant = Superficie2,
    onSurfaceVariant = TextoFraco,
    error = Alerta,
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // uma unica instancia de cada: evita varios SoundPool concorrentes
        SomToque.inicializar(this)
        Toque.inicializar(this)
        // controle remoto sem a tela apagar no meio do uso
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme(colorScheme = Cores) {
                AppRaiz()
            }
        }
    }
}

@Composable
private fun AppRaiz() {
    val contexto = LocalContext.current
    val prefs = remember { Prefs(contexto) }
    var config by remember { mutableStateOf(prefs.carregar()) }
    var emConfig by rememberSaveable { mutableStateOf(!config.valida) }
    val escopo = rememberCoroutineScope()
    val conexao = remember { Conexao(escopo, contexto.applicationContext) }

    LaunchedEffect(config, emConfig) {
        if (emConfig) {
            conexao.desconectar()
        } else if (config.valida) {
            conexao.conectar(config)
        }
    }

    if (emConfig) {
        // voltar do sistema deve retornar ao controle, nao fechar o app
        BackHandler(enabled = config.valida) { emConfig = false }
        TelaConfig(
            inicial = config,
            aoSalvar = { nova ->
                config = nova
                prefs.salvar(nova)
                emConfig = false
            },
            aoCancelar = if (config.valida) {
                { emConfig = false }
            } else {
                null
            },
        )
    } else {
        TelaControle(
            config = config,
            conexao = conexao,
            aoAbrirConfig = { emConfig = true },
        )
    }
}
