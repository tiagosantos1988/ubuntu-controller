package com.tsantos.controle

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.OutlinedButton
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TelaConfig(
    inicial: Config,
    aoSalvar: (Config) -> Unit,
    aoCancelar: (() -> Unit)?,
) {
    var ip by remember { mutableStateOf(inicial.ip) }
    var porta by remember { mutableStateOf(inicial.porta.toString()) }
    var token by remember { mutableStateOf(inicial.token) }
    var sensibilidade by remember { mutableFloatStateOf(inicial.sensibilidade) }
    var aviso by remember { mutableStateOf<String?>(null) }
    val contexto = LocalContext.current.applicationContext
    // hoisted: dentro do onClick nao ha contexto de composicao
    val dicaQr = stringResource(R.string.config_qr_prompt)

    // Le o QR code mostrado na janela do app do Ubuntu e preenche os campos.
    val leitor = rememberLauncherForActivityResult(ScanContract()) { resultado ->
        val texto = resultado.contents
        if (texto != null) {
            val lida = lerCodigoQr(texto)
            if (lida == null) {
                aviso = contexto.getString(R.string.config_qr_invalido)
            } else {
                ip = lida.ip
                porta = lida.porta.toString()
                token = lida.token
                aviso = null
            }
        }
    }

    val portaNum = porta.toIntOrNull() ?: 0
    val valido = ip.isNotBlank() && portaNum in 1..65535 && token.length >= 8

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            stringResource(R.string.config_titulo),
            style = MaterialTheme.typography.headlineSmall,
            color = Texto,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.config_intro),
            color = TextoFraco,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = {
                leitor.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt(dicaQr)
                        .setBeepEnabled(false)
                )
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Primaria),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Primaria),
        ) {
            Text(stringResource(R.string.config_qr_botao), fontWeight = FontWeight.Medium, fontSize = 15.sp)
        }

        aviso?.let { mensagem ->
            Spacer(Modifier.height(8.dp))
            Text(mensagem, color = Alerta, fontSize = 12.sp, textAlign = TextAlign.Center,
                 modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.config_ou_manual),
            color = TextoFraco,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))

        Campo(
            rotulo = stringResource(R.string.config_ip),
            valor = ip,
            aoMudar = { novo -> ip = novo.filter { it.isDigit() || it == '.' }.take(15) },
            dica = "192.168.10.16",
        )
        Spacer(Modifier.height(12.dp))

        Campo(
            rotulo = stringResource(R.string.config_porta),
            valor = porta,
            aoMudar = { novo -> porta = novo.filter { it.isDigit() }.take(5) },
            dica = "8765",
        )
        Spacer(Modifier.height(12.dp))

        Campo(
            rotulo = stringResource(R.string.config_token),
            valor = token,
            aoMudar = { novo -> token = novo.trim().take(64) },
            dica = stringResource(R.string.config_token_dica),
        )

        Spacer(Modifier.height(22.dp))
        Text(stringResource(R.string.config_sensibilidade), color = Texto, fontSize = 14.sp)
        Text(
            stringResource(R.string.config_sensibilidade_dica),
            color = TextoFraco,
            fontSize = 12.sp,
        )
        Slider(
            value = sensibilidade,
            onValueChange = { sensibilidade = it },
            valueRange = 0.5f..4.0f,
            colors = SliderDefaults.colors(
                thumbColor = Primaria,
                activeTrackColor = Primaria,
                inactiveTrackColor = Superficie2,
            ),
        )
        Text("${"%.1f".format(sensibilidade)}x", color = Primaria, fontSize = 13.sp)

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { aoSalvar(Config(ip, portaNum, token, sensibilidade)) },
            enabled = valido,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Primaria,
                contentColor = Color(0xFF04121C),
                disabledContainerColor = Superficie2,
                disabledContentColor = TextoFraco,
            ),
        ) {
            Text(stringResource(R.string.config_salvar), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }

        if (aoCancelar != null) {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = aoCancelar, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.config_voltar), color = TextoFraco, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(26.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Superficie)
                .padding(14.dp)
        ) {
            Text(stringResource(R.string.config_onde_token), color = Texto, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.config_onde_token_texto),
                color = TextoFraco,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun Campo(
    rotulo: String,
    valor: String,
    aoMudar: (String) -> Unit,
    dica: String,
) {
    OutlinedTextField(
        value = valor,
        onValueChange = aoMudar,
        label = { Text(rotulo) },
        placeholder = { Text(dica, color = TextoFraco.copy(alpha = 0.6f)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Primaria,
            unfocusedBorderColor = Superficie2,
            focusedTextColor = Texto,
            unfocusedTextColor = Texto,
            focusedLabelColor = Primaria,
            unfocusedLabelColor = TextoFraco,
            cursorColor = Primaria,
            focusedContainerColor = Superficie,
            unfocusedContainerColor = Superficie,
        ),
    )
}

private data class ConfigLida(val ip: String, val porta: Int, val token: String)

/**
 * Formato gerado pelo app do Ubuntu: CR1|ip|porta|token
 * (curto de proposito: payload menor gera um QR com menos modulos e mais
 * facil de ler a distancia).
 */
private fun lerCodigoQr(texto: String): ConfigLida? {
    val partes = texto.trim().split("|")
    if (partes.size != 4 || partes[0] != "CR1") return null
    val porta = partes[2].toIntOrNull() ?: return null
    if (partes[1].isBlank() || partes[3].length < 8) return null
    return ConfigLida(partes[1], porta, partes[3])
}
