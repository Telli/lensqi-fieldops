package com.lensqi.fieldops.ui.inspection

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lensqi.fieldops.ui.common.PrimaryButton
import com.lensqi.fieldops.ui.common.SecondaryButton
import com.lensqi.fieldops.ui.theme.Fo
import com.lensqi.fieldops.voice.rememberVoiceInput

/**
 * The repair claim, typed, dictated or recorded. Whichever way the words arrive, the person reviews them first and they
 * become a claim only through [onSubmit], the same path for the first repair and for a retry after failed verification.
 */
@Composable
fun RepairClaimInput(
    text: String,
    onTextChanged: (String) -> Unit,
    label: String,
    submitLabel: String,
    submitIcon: ImageVector,
    enabled: Boolean,
    onSubmit: (String) -> Unit,
    voice: VoiceClaimViewModel = hiltViewModel(),
) {
    val s by voice.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && enabled && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) voice.startRecording()
        else if (!granted) voice.permissionDenied()
    }
    DisposableEffect(owner, voice) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) voice.leaveScreen() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); voice.leaveScreen() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (s.phase) {
            VoiceClaimPhase.Idle -> {
                // Dictation edits only the draft: the person reviews it before pressing the separate submit button.
                val dictation = rememberVoiceInput(onFinal = onTextChanged)
                Field(text, onTextChanged, label)
                if (dictation.supported) {
                    TextButton(onClick = { if (dictation.listening) dictation.stop() else dictation.start() }) {
                        Text(if (dictation.listening) "Finish dictating" else "Dictate repair claim")
                    }
                    if (dictation.listening) Text(dictation.heard.ifBlank { "Listening…" })
                    Muted("Review the text before recording the repair. Voice uses your phone’s speech service.")
                    dictation.error?.let { Text(it, color = Fo.Warning) }
                }
                // One microphone: the recorder stays closed while dictation holds it.
                SecondaryButton("Record with Muse Voice", {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) voice.startRecording()
                    else permission.launch(Manifest.permission.RECORD_AUDIO)
                }, Modifier.fillMaxWidth(), enabled = enabled && !dictation.listening, icon = Icons.Outlined.Mic)
                Muted("Recording sends your audio through the LensQi server to Meta for transcription. LensQi does not save the audio.")
                PrimaryButton(submitLabel, { onSubmit(text) }, Modifier.fillMaxWidth(), enabled = enabled && text.isNotBlank(), icon = submitIcon)
            }
            VoiceClaimPhase.Recording -> {
                Text("Recording… Tap stop when finished (up to 60 seconds).", color = Fo.Primary)
                PrimaryButton("Stop and transcribe", voice::stopAndTranscribe, Modifier.fillMaxWidth())
                SecondaryButton("Cancel recording", voice::reject, Modifier.fillMaxWidth())
            }
            VoiceClaimPhase.Transcribing -> {
                Text("Transcribing your repair claim…")
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Fo.Primary)
                SecondaryButton("Cancel transcription", voice::reject, Modifier.fillMaxWidth())
            }
            VoiceClaimPhase.Review -> {
                Text("Review transcript · Muse Voice (inferred)", style = MaterialTheme.typography.labelLarge)
                Muted("Check and edit these words. Confirming submits them as your repair claim.")
                OutlinedTextField(s.transcript, voice::editTranscript, label = { Text("Repair claim transcript") },
                    modifier = Modifier.fillMaxWidth(), minLines = 3, shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Fo.Primary, unfocusedBorderColor = Fo.Border),
                    isError = s.transcript.length > VoiceClaimViewModel.MAX_CHARS,
                    supportingText = { Text("${s.transcript.length}/${VoiceClaimViewModel.MAX_CHARS}") })
                PrimaryButton("Confirm transcript and submit claim", {
                    // The words stay in the claim field, so a refused or failed submission can be retried from there.
                    voice.confirm()?.let { confirmed -> onTextChanged(confirmed); onSubmit(confirmed) }
                }, Modifier.fillMaxWidth(), enabled = enabled && s.transcript.isNotBlank() && s.transcript.length <= VoiceClaimViewModel.MAX_CHARS)
                SecondaryButton("Reject transcript", voice::reject, Modifier.fillMaxWidth())
            }
        }
        s.error?.let { Text(it, color = Fo.Failure, style = MaterialTheme.typography.bodySmall) }
    }
}
