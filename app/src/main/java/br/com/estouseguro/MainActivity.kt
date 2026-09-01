package br.com.estouseguro

import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.KeyEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import br.com.estouseguro.domain.model.BloodType
import br.com.estouseguro.domain.model.BrazilianCpf
import br.com.estouseguro.domain.model.BrazilianDate
import br.com.estouseguro.domain.model.BrazilianPhoneNumber
import br.com.estouseguro.domain.model.DashboardSnapshot
import br.com.estouseguro.domain.model.EmergencyMedicalProfile
import br.com.estouseguro.domain.model.DocumentType
import br.com.estouseguro.domain.model.IdentityDocument
import br.com.estouseguro.domain.model.SmsDeliveryAttempt
import br.com.estouseguro.domain.model.SmsDeliveryStatus
import br.com.estouseguro.domain.model.SmsEmergencyCategory
import br.com.estouseguro.domain.model.TrustedContact
import br.com.estouseguro.domain.usecase.PreparedAlert
import br.com.estouseguro.domain.repository.DocumentSide
import br.com.estouseguro.platform.ShareDispatcher
import br.com.estouseguro.platform.SmsAlertDispatcher
import br.com.estouseguro.platform.SmsDispatchResult
import br.com.estouseguro.platform.backend.BackendAlertCategory
import br.com.estouseguro.platform.backend.BackendActivationRequiredException
import br.com.estouseguro.platform.backend.CloudAlertResult
import br.com.estouseguro.platform.backend.SandboxBackendClient
import java.text.DateFormat
import java.io.File
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.Date

class MainActivity : android.app.Activity() {
    private val container: AppContainer
        get() = (application as EstouSeguroApplication).container

    private var currentSnapshot = DashboardSnapshot(emptyList(), null)
    private var pendingEmergency = false
    private var unlockInProgress = false
    private var sessionUnlocked = false
    private var hasStartedOnce = false
    private var pendingShortcutEmergency = false
    private var pendingEmergencyType: EmergencyType = EmergencyType.GENERAL
    private var pendingPreparedAlert: PreparedAlert? = null
    private var smsDispatchInProgress = false
    private var pendingDocumentImage: PendingDocumentImage? = null
    private var pendingCaptureFile: File? = null
    private var backendActivationDialog: AlertDialog? = null
    private val pendingBackendRetries = mutableListOf<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NAVY_DARK
        window.navigationBarColor = SURFACE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        pendingShortcutEmergency = intent.getBooleanExtra(EXTRA_OPEN_EMERGENCY, false)
        if (container.sessionRepository.hasCredential()) showUnlock() else showRegistration()
    }

    override fun onStart() {
        super.onStart()
        if (hasStartedOnce && container.sessionRepository.hasCredential() && !sessionUnlocked) {
            showUnlock()
        }
        hasStartedOnce = true
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            sessionUnlocked = false
            unlockInProgress = false
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_EMERGENCY, false)) {
            pendingShortcutEmergency = true
            if (sessionUnlocked) showDashboard() else showUnlock()
        }
    }

    private fun showRegistration() {
        val content = screenContainer()
        content.addView(brandHeader("Estou Seguro", "Sua rede de proteção sempre por perto"))

        val form = card().apply {
            addView(overline("PRIMEIRO ACESSO"))
            addView(cardTitle("Crie seu acesso"))
            addView(paragraph("Seus dados ficam protegidos neste aparelho. Você poderá configurar sua rede de confiança em seguida."))
        }
        val name = input("Seu nome", InputType.TYPE_CLASS_TEXT).apply {
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            setAutofillHints(View.AUTOFILL_HINT_NAME)
        }
        val pinEntry = pinEntry("PIN de 4 a 8 números")
        val pin = pinEntry.input
        form.addView(fieldLabel("NOME"))
        form.addView(name)
        form.addView(fieldLabel("PIN DE ACESSO"))
        form.addView(pinEntry.view)
        form.addView(primaryButton("Criar acesso") {
            val nameValue = name.text.toString()
            val pinValue = pin.text.toString().toCharArray()
            runIo(
                action = { container.sessionRepository.register(nameValue, pinValue) },
                onSuccess = {
                    sessionUnlocked = true
                    showDashboard()
                },
                onError = { showError(it.message ?: "Não foi possível criar o acesso.") },
            )
        })
        content.addView(form, blockParams())
        content.addView(privacyNote())
        setContentView(wrap(content))
    }

    private fun showUnlock() {
        unlockInProgress = false
        val content = screenContainer()
        content.addView(brandHeader("Estou Seguro", "Bem-vindo de volta"))

        val form = card().apply {
            addView(overline("ÁREA PROTEGIDA"))
            addView(cardTitle("Olá, ${container.sessionRepository.displayName()}"))
            addView(paragraph("Digite seu PIN para acessar contatos e informações sensíveis."))
        }
        val pinEntry = pinEntry("Seu PIN")
        val pin = pinEntry.input.apply {
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(InputFilter.LengthFilter(8))
            contentDescription = "PIN de acesso, de 4 a 8 números"
        }
        val feedback = TextView(this).apply {
            textSize = 13f
            setTextColor(RED_DARK)
            setPadding(dp(2), 0, dp(2), dp(10))
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        }
        form.addView(fieldLabel("PIN DE ACESSO"))
        form.addView(pinEntry.view)
        form.addView(feedback)
        val unlockButton = primaryButton("Desbloquear") {}

        fun setLoading(loading: Boolean) {
            unlockInProgress = loading
            pin.isEnabled = !loading
            unlockButton.isEnabled = !loading
            unlockButton.isClickable = !loading
            unlockButton.alpha = if (loading) 0.72f else 1f
            unlockButton.text = if (loading) "Validando PIN..." else "Desbloquear"
            unlockButton.contentDescription = if (loading) {
                "Validando PIN, aguarde"
            } else {
                "Desbloquear acesso"
            }
            if (loading) unlockButton.announceForAccessibility("Validando PIN")
        }

        fun showInlineError(message: String) {
            feedback.text = message
            feedback.visibility = View.VISIBLE
            feedback.announceForAccessibility(message)
            pin.requestFocus()
        }

        fun submit() {
            if (unlockInProgress) return
            feedback.visibility = View.GONE
            pin.error = null
            val rawPin = pin.text.toString()
            if (rawPin.length !in 4..8 || rawPin.any { !it.isDigit() }) {
                showInlineError("Digite seu PIN de 4 a 8 números.")
                return
            }
            hideKeyboard(pin)
            setLoading(true)
            val candidate = rawPin.toCharArray()
            runIo(
                action = { container.sessionRepository.authenticate(candidate) },
                onSuccess = { authenticated ->
                    if (authenticated) {
                        sessionUnlocked = true
                        showDashboard()
                    } else {
                        setLoading(false)
                        pin.text.clear()
                        showInlineError("PIN incorreto. Confira os números e tente novamente.")
                    }
                },
                onError = {
                    candidate.fill('\u0000')
                    setLoading(false)
                    showInlineError("Não foi possível validar agora. Tente novamente.")
                },
            )
        }

        unlockButton.setOnClickListener { submit() }
        pin.setOnEditorActionListener { _, actionId, event ->
            val submitted = actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (submitted) submit()
            submitted
        }
        form.addView(unlockButton)
        content.addView(form, blockParams())
        content.addView(lockedEmergencyButton(), blockParams(8))
        content.addView(helperText("Pressão longa aciona o SMS sem revelar contatos ou dados médicos."))
        content.addView(privacyNote())
        content.addView(brandSpotlight())
        setContentView(wrap(content))
    }

    private fun showDashboard() {
        runIo(
            action = {
                val snapshot = container.loadDashboard()
                snapshot to container.loadSmsDeliveryStatus(snapshot.latestAlert?.id)
            },
            onSuccess = { (snapshot, deliveries) ->
                if (!sessionUnlocked) return@runIo
                currentSnapshot = snapshot
                renderDashboard(snapshot, deliveries)
            },
            onError = { showError("Não foi possível carregar seus dados.") },
        )
    }

    private fun renderDashboard(snapshot: DashboardSnapshot, deliveries: List<SmsDeliveryAttempt>) {
        val content = screenContainer()
        content.addView(
            brandHeader(
                "Olá, ${container.sessionRepository.displayName()}",
                "Central de segurança",
            ),
        )
        content.addView(readinessCard(snapshot), blockParams())

        val emergencyCard = card().apply {
            addView(overline("EMERGÊNCIA"))
            addView(cardTitle("Está precisando de ajuda?"))
            addView(paragraph("Prepare um alerta com sua última localização conhecida e avise sua rede de confiança."))
            addView(emergencyButton())
            addView(emergencyTypeSelector())
            addView(helperText("Após sua confirmação, o app tenta enviar um SMS individual para cada contato e informa falhas detectadas."))
        }
        content.addView(emergencyCard, blockParams())
        content.addView(protectionSupportCard(), blockParams())

        content.addView(sectionHeader("Rede de confiança", "${snapshot.contacts.size} cadastrado(s)"))
        if (snapshot.contacts.isEmpty()) {
            content.addView(emptyContactsCard(), blockParams())
        } else {
            snapshot.contacts.forEach { content.addView(contactCard(it), blockParams(8)) }
        }
        content.addView(outlineButton("+  Adicionar contato") { showContactDialog() })

        content.addView(sectionHeader("Ações rápidas", null))
        content.addView(actionButton("✓", "Cheguei bem", "Enviar um check-in para seus contatos") { performCheckIn() })
        content.addView(actionButton("+", "Ficha médica", "Dados opcionais protegidos no aparelho") {
            loadMedicalProfile()
        })
        content.addView(actionButton("▣", "Cofre de documentos", "CPF, CIN/RG, CNH, CTPS e fotos protegidas") {
            loadDocumentVault()
        })
        content.addView(actionButton("☎", "Ligar 190", "Abrir o discador para emergência no Brasil") {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:190")))
        })
        content.addView(actionButton("☎", "Ligar SAMU 192", "Urgência e emergência médica") {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:192")))
        })
        content.addView(actionButton("☎", "Ligar Bombeiros 193", "Incêndio, resgate e salvamento") {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:193")))
        })

        content.addView(sectionHeader("Atividade recente", null))
        content.addView(activityCard(snapshot, deliveries), blockParams())
        content.addView(legalNotice())
        setContentView(wrap(content))
        if (pendingShortcutEmergency) {
            pendingShortcutEmergency = false
            content.post { if (!isFinishing && sessionUnlocked) confirmEmergency() }
        }
    }

    private fun readinessCard(snapshot: DashboardSnapshot): View {
        val validCount = snapshot.contacts.count { BrazilianPhoneNumber.normalizeForSms(it.phone) != null }
        val ready = snapshot.contacts.isNotEmpty() && validCount == snapshot.contacts.size
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(if (ready) GREEN_SOFT else AMBER_SOFT, 18)
            addView(TextView(this@MainActivity).apply {
                text = if (ready) "✓" else "!"
                gravity = Gravity.CENTER
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (ready) GREEN else AMBER)
                background = oval(Color.WHITE)
            }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = if (ready) "Rede pronta" else "Configuração incompleta"
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (ready) GREEN_DARK else AMBER_DARK)
                })
                addView(TextView(this@MainActivity).apply {
                    text = if (ready) {
                        "$validCount contato(s) válido(s) para alertas"
                    } else if (snapshot.contacts.isNotEmpty()) {
                        "Corrija ${snapshot.contacts.size - validCount} telefone(s) incompleto(s)"
                    } else {
                        "Adicione alguém para liberar alertas e check-ins"
                    }
                    textSize = 13f
                    setTextColor(TEXT_MUTED)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun emergencyButton() = Button(this).apply {
        text = getString(R.string.emergency_button)
        textSize = 18f
        isAllCaps = false
        letterSpacing = 0.03f
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(RED, RED_DARK),
        ).apply { cornerRadius = dp(22).toFloat() }
        elevation = dp(5).toFloat()
        contentDescription = "Preparar e compartilhar alerta de emergência"
        setOnClickListener { confirmEmergency(EmergencyType.GENERAL) }
        layoutParams = blockParams(heightDp = 72, marginBottomDp = 10)
    }

    private fun lockedEmergencyButton() = Button(this).apply {
        text = getString(R.string.locked_sos_button)
        textSize = 15f
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = rounded(RED_DARK, 18)
        contentDescription = getString(R.string.locked_sos_action)
        setOnLongClickListener {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            beginLockedEmergency()
            true
        }
        layoutParams = blockParams(heightDp = 62, marginBottomDp = 8)
    }

    private fun emergencyTypeSelector(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@MainActivity).apply {
            text = getString(R.string.emergency_type_prompt)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TEXT_MUTED)
            setPadding(dp(2), dp(4), 0, dp(8))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setAccessibilityHeading(true)
        })
        addView(emergencyTypeButton(
            icon = "✚",
            title = "Acidente ou emergência médica",
            description = "Preparar alerta médico para sua rede",
            type = EmergencyType.MEDICAL,
        ))
        addView(emergencyTypeButton(
            icon = "!",
            title = "Roubo ou sequestro",
            description = "Preparar alerta de risco com localização",
            type = EmergencyType.SECURITY,
        ))
        addView(emergencyTypeButton(
            icon = "♀",
            title = "Violência contra a mulher",
            description = "Agressão física, psicológica, sexual ou ameaça",
            type = EmergencyType.DOMESTIC_VIOLENCE,
        ))
        addView(emergencyTypeButton(
            icon = "✦",
            title = "Criança ou adolescente em risco",
            description = "Violência, abuso, abandono ou desaparecimento",
            type = EmergencyType.CHILD_DANGER,
        ))
        addView(emergencyTypeButton(
            icon = "♡",
            title = "Crise de ansiedade",
            description = "Pedir apoio imediato à sua rede",
            type = EmergencyType.ANXIETY,
        ))
    }

    private fun emergencyTypeButton(
        icon: String,
        title: String,
        description: String,
        type: EmergencyType,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = rounded(Color.rgb(250, 252, 255), 14, BORDER)
        isClickable = true
        isFocusable = true
        contentDescription = "$title. $description"
        setOnClickListener { confirmEmergency(type) }
        addView(TextView(this@MainActivity).apply {
            text = icon
            gravity = Gravity.CENTER
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            val isDanger = type == EmergencyType.SECURITY ||
                type == EmergencyType.DOMESTIC_VIOLENCE || type == EmergencyType.CHILD_DANGER
            setTextColor(if (isDanger) RED_DARK else NAVY)
            background = rounded(if (isDanger) Color.rgb(255, 237, 235) else BLUE_SOFT, 11)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(10) })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(TEXT)
            })
            addView(TextView(this@MainActivity).apply {
                text = description
                textSize = 11f
                setTextColor(TEXT_MUTED)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@MainActivity).apply {
            text = "›"
            textSize = 23f
            setTextColor(TEXT_LIGHT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        layoutParams = blockParams(marginBottomDp = 7)
    }

    private fun protectionSupportCard(): View = card().apply {
        addView(overline("PROTEÇÃO DE MULHERES E CRIANÇAS"))
        addView(cardTitle("Você não está sozinha"))
        addView(paragraph("Ameaças, humilhação, controle, perseguição, abuso e agressão também são violência. Em perigo agora, ligue 190. Para orientação e denúncia, use os canais abaixo."))
        addView(actionButton("☎", "Perigo imediato — 190", "Polícia Militar") {
            dialProtectionChannel("190")
        })
        addView(actionButton("♀", "Ligue 180", "Atendimento à mulher, gratuito e 24 horas") {
            dialProtectionChannel("180")
        })
        addView(actionButton("✦", "Disque 100", "Violações contra crianças e direitos humanos") {
            dialProtectionChannel("100")
        })
        addView(outlineButton("Entender os sinais e canais de ajuda") { showProtectionGuidance() })
    }

    private fun dialProtectionChannel(number: String) {
        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
    }

    private fun showProtectionGuidance() {
        val message = """
            PERIGO IMEDIATO
            Ligue 190. O SOS do app também avisa sua rede com a localização disponível.

            VIOLÊNCIA CONTRA A MULHER
            Pode ser física, psicológica, sexual, patrimonial ou moral. Ameaça, humilhação, controle do dinheiro, isolamento, perseguição e vigilância constante são sinais importantes.

            CRIANÇAS E ADOLESCENTES
            Violência, abuso, exploração, negligência, abandono ou desaparecimento podem ser comunicados pelo Disque 100. Em risco imediato, ligue 190.

            LIGUE 180
            Orientação, denúncia e localização da rede especializada de atendimento à mulher. Serviço gratuito, 24 horas.

            Este aplicativo não substitui polícia, serviços de saúde, Conselho Tutelar ou atendimento profissional.
        """.trimIndent()
        AlertDialog.Builder(this)
            .setTitle("Proteção e canais de ajuda")
            .setMessage(message)
            .setNegativeButton("Disque 100") { _, _ -> dialProtectionChannel("100") }
            .setNeutralButton("Ligue 180") { _, _ -> dialProtectionChannel("180") }
            .setPositiveButton("Ligue 190") { _, _ -> dialProtectionChannel("190") }
            .show()
    }

    private fun contactCard(contact: TrustedContact): View = card(compact = true).apply {
        val phoneIsValid = BrazilianPhoneNumber.normalizeForSms(contact.phone) != null
        val infoRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        infoRow.addView(TextView(this@MainActivity).apply {
            text = contact.name.trim().firstOrNull()?.uppercase() ?: "?"
            gravity = Gravity.CENTER
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(NAVY)
            background = oval(BLUE_SOFT)
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) })
        infoRow.addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = contact.name
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(TEXT)
            })
            addView(TextView(this@MainActivity).apply {
                text = contact.phone
                textSize = 14f
                setTextColor(if (phoneIsValid) TEXT_MUTED else RED_DARK)
            })
            if (!phoneIsValid) addView(TextView(this@MainActivity).apply {
                text = getString(R.string.invalid_mobile_number)
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(RED_DARK)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(infoRow)

        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10), 0, 0)
            addView(smallActionButton("Editar", NAVY) { showContactDialog(contact) })
            addView(smallActionButton("Remover", RED) { confirmDelete(contact) })
        })
    }

    private fun emptyContactsCard(): View = card().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(TextView(this@MainActivity).apply {
            text = "+"
            gravity = Gravity.CENTER
            textSize = 26f
            setTextColor(NAVY)
            background = oval(BLUE_SOFT)
        }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { bottomMargin = dp(12) })
        addView(cardTitle("Monte sua rede de confiança").apply { gravity = Gravity.CENTER })
        addView(paragraph("Cadastre ao menos uma pessoa para poder preparar alertas e check-ins.").apply {
            gravity = Gravity.CENTER
        })
    }

    private fun actionButton(icon: String, title: String, subtitle: String, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(Color.WHITE, 18, BORDER)
            elevation = dp(1).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(TextView(this@MainActivity).apply {
                text = icon
                gravity = Gravity.CENTER
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(NAVY)
                background = rounded(BLUE_SOFT, 14)
            }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(12) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = title
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(TEXT)
                })
                addView(TextView(this@MainActivity).apply {
                    text = subtitle
                    textSize = 13f
                    setTextColor(TEXT_MUTED)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "›"
                textSize = 28f
                setTextColor(TEXT_LIGHT)
            })
            layoutParams = blockParams(marginBottomDp = 10)
        }

    private fun activityCard(
        snapshot: DashboardSnapshot,
        deliveries: List<SmsDeliveryAttempt>,
    ): View = card(compact = true).apply {
        val activityText = snapshot.latestAlert?.let {
            "Alerta preparado em ${DateFormat.getDateTimeInstance().format(Date(it.createdAtEpochMillis))}. " +
                (if (it.location == null) "Sem localização." else "Com última localização conhecida.") +
                deliverySummary(deliveries)
        } ?: "Nenhum alerta registrado neste dispositivo."
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "◷"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(NAVY)
                background = rounded(BLUE_SOFT, 12)
            }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) })
            addView(TextView(this@MainActivity).apply {
                text = activityText
                textSize = 14f
                setTextColor(TEXT_MUTED)
                setLineSpacing(0f, 1.1f)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
    }

    private fun deliverySummary(attempts: List<SmsDeliveryAttempt>): String {
        if (attempts.isEmpty()) return ""
        val recipientStates = attempts.groupBy { it.recipient }.values.map { parts ->
            when {
                parts.any { it.status == SmsDeliveryStatus.SEND_FAILED || it.status == SmsDeliveryStatus.DELIVERY_FAILED } -> "falha"
                parts.all { it.status == SmsDeliveryStatus.DELIVERED } -> "entregue"
                parts.all { it.status == SmsDeliveryStatus.HANDED_TO_RADIO || it.status == SmsDeliveryStatus.DELIVERED } -> "enviado"
                else -> "na fila"
            }
        }
        fun count(state: String) = recipientStates.count { it == state }
        return " SMS: ${count("entregue")} entregue(s), ${count("enviado")} enviado(s), " +
            "${count("na fila")} na fila e ${count("falha")} com falha."
    }

    private fun showContactDialog(existing: TrustedContact? = null) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val name = input("Nome", InputType.TYPE_CLASS_TEXT)
        val phone = input("Ex.: (33) 9 9999-9999", InputType.TYPE_CLASS_PHONE)
        if (existing != null) {
            name.setText(existing.name)
            phone.setText(existing.phone)
        }
        form.addView(fieldLabel("NOME"))
        form.addView(name)
        form.addView(fieldLabel("TELEFONE"))
        form.addView(phone)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Novo contato de confiança" else "Editar contato")
            .setView(form)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runIo(
                    action = {
                        if (existing == null) {
                            container.manageContacts.add(name.text.toString(), phone.text.toString())
                        } else {
                            container.manageContacts.update(existing.id, name.text.toString(), phone.text.toString())
                        }
                    },
                    onSuccess = { saved ->
                        dialog.dismiss()
                        showDashboard()
                        syncContactWithBackend(saved)
                    },
                    onError = { showError(it.message ?: "Contato inválido ou já cadastrado.") },
                )
            }
        }
        dialog.show()
    }

    private fun confirmDelete(contact: TrustedContact) {
        AlertDialog.Builder(this)
            .setTitle("Remover contato?")
            .setMessage("${contact.name} deixará de receber seus alertas e check-ins.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Remover") { _, _ ->
                runIo(
                    action = { container.manageContacts.delete(contact.id) },
                    onSuccess = {
                        showDashboard()
                        removeContactFromBackend(contact.id)
                    },
                    onError = { showError("Não foi possível remover o contato.") },
                )
            }
            .show()
    }

    private fun confirmEmergency(type: EmergencyType = EmergencyType.GENERAL) {
        pendingEmergencyType = type
        AlertDialog.Builder(this)
            .setTitle(type.confirmationTitle)
            .setMessage("${type.confirmationMessage}\n\nBuscaremos a última localização disponível e tentaremos enviar um SMS individual para cada contato. O aparelho pedirá a permissão de SMS no primeiro uso e sua operadora poderá cobrar pelo envio.${if (type == EmergencyType.MEDICAL) " Os dados preenchidos na ficha médica serão incluídos." else ""}")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Continuar") { _, _ -> beginEmergency() }
            .show()
    }

    private fun beginEmergency() {
        if (currentSnapshot.contacts.isEmpty()) {
            showError("Cadastre ao menos um contato de confiança.")
            return
        }
        if (currentSnapshot.contacts.none { BrazilianPhoneNumber.normalizeForSms(it.phone) != null }) {
            showError("Corrija o telefone dos contatos. Celular precisa de DDD + 9 dígitos.")
            return
        }
        if (!container.locationProvider.hasPermission()) {
            pendingEmergency = true
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_REQUEST,
            )
            return
        }
        prepareAndShareAlert()
    }

    private fun beginLockedEmergency() {
        if (smsDispatchInProgress) return
        pendingEmergencyType = EmergencyType.GENERAL
        runIo(
            action = container.loadDashboard::invoke,
            onSuccess = { snapshot ->
                currentSnapshot = snapshot
                if (snapshot.contacts.none { BrazilianPhoneNumber.normalizeForSms(it.phone) != null }) {
                    showError("Desbloqueie e corrija um contato: celular precisa de DDD + 9 dígitos.")
                    return@runIo
                }
                if (!container.locationProvider.hasPermission()) {
                    Toast.makeText(
                        this,
                        "Localização não autorizada; enviando SOS sem localização.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                prepareAndShareAlert()
            },
            onError = { showError("Não foi possível carregar sua rede de emergência.") },
        )
    }

    private fun prepareAndShareAlert() {
        val location = container.locationProvider.lastKnownLocation()
        runIo(
            action = {
                val prepared = container.prepareEmergencyAlert.execute(location)
                if (pendingEmergencyType == EmergencyType.MEDICAL) {
                    val medical = container.manageMedicalProfile.load()
                    prepared.copy(message = pendingEmergencyType.messagePrefix + prepared.message + medicalSummary(medical))
                } else {
                    prepared.copy(message = pendingEmergencyType.messagePrefix + prepared.message)
                }
            },
            onSuccess = { prepared ->
                pendingPreparedAlert = prepared
                enqueueCloudAlert(prepared, pendingEmergencyType.backendCategory())
                dispatchPreparedAlert()
            },
            onError = { showError(it.message ?: "Não foi possível preparar o alerta.") },
        )
    }

    private fun dispatchPreparedAlert() {
        val prepared = pendingPreparedAlert ?: return
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.SEND_SMS), SMS_REQUEST)
            return
        }
        if (smsDispatchInProgress) return
        smsDispatchInProgress = true
        runIo(
            action = {
                container.smsAlertDispatcher.send(
                    prepared.alert.id,
                    prepared.smsMessage(pendingEmergencyType.smsCategory()),
                    prepared.recipients,
                )
            },
            onSuccess = { result ->
                smsDispatchInProgress = false
                handleSmsDispatchResult(prepared, result)
            },
            onError = {
                smsDispatchInProgress = false
                showSmsFallback(prepared, "O Android recusou a tentativa de envio direto.")
            },
        )
    }

    private fun handleSmsDispatchResult(prepared: PreparedAlert, result: SmsDispatchResult) {
        when (result) {
            is SmsDispatchResult.PermissionRequired -> requestPermissions(
                arrayOf(Manifest.permission.SEND_SMS), SMS_REQUEST,
            )
            is SmsDispatchResult.Accepted -> {
                if (result.queuedPartCount == 0) {
                    showSmsFallback(prepared, "Nenhum SMS foi aceito pelo aparelho.")
                    return
                }
                pendingPreparedAlert = null
                showSmsProgressDialog(prepared, result)
            }
            SmsDispatchResult.NoRecipients -> showSmsFallback(
                prepared,
                "Nenhum telefone válido foi encontrado. Celular brasileiro precisa de DDD + 9 dígitos.",
            )
            SmsDispatchResult.UnsupportedDevice -> showSmsFallback(prepared, "Este aparelho não oferece envio direto de SMS.")
        }
    }

    private fun showSmsProgressDialog(prepared: PreparedAlert, accepted: SmsDispatchResult.Accepted) {
        val message = TextView(this).apply {
            textSize = 16f
            setTextColor(TEXT)
            setPadding(dp(24), dp(12), dp(24), dp(12))
            text = "SMS automático iniciado para ${accepted.recipientCount} contato(s), um por vez. Aguardando resposta do modem..."
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Envio automático do SOS")
            .setView(message)
            .setNegativeButton("Sobre WhatsApp automático") { _, _ -> showWhatsAppAutomationExplanation() }
            .setPositiveButton("Fechar", null)
            .create()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val startedAt = System.currentTimeMillis()
        lateinit var poll: Runnable
        poll = Runnable {
            if (!dialog.isShowing) return@Runnable
            runIo(
                action = { container.loadSmsDeliveryStatus(prepared.alert.id) },
                onSuccess = { attempts ->
                    if (!dialog.isShowing) return@runIo
                    message.text = smsProgressText(prepared, accepted.recipientCount, attempts)
                    val responded = attempts.groupBy { it.recipient }.count { (_, parts) ->
                        parts.isNotEmpty() && parts.all { it.status != SmsDeliveryStatus.QUEUED }
                    }
                    if (responded < accepted.recipientCount && System.currentTimeMillis() - startedAt < 65_000) {
                        handler.postDelayed(poll, 1_500)
                    }
                },
                onError = { message.text = "Não foi possível consultar o retorno do modem. Consulte Atividade recente." },
            )
        }
        dialog.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            showDashboard()
        }
        dialog.show()
        handler.postDelayed(poll, 800)
    }

    private fun smsProgressText(
        prepared: PreparedAlert,
        recipientCount: Int,
        attempts: List<SmsDeliveryAttempt>,
    ): String {
        val byPhone = prepared.recipients.associateBy { BrazilianPhoneNumber.normalizeForSms(it.phone) }
        val rows = attempts.groupBy { it.recipient }.map { (phone, parts) ->
            val name = byPhone[phone]?.name ?: "Contato final ${phone.takeLast(4)}"
            val status = when {
                parts.any { it.status == SmsDeliveryStatus.SEND_FAILED } -> {
                    val code = parts.firstOrNull { it.status == SmsDeliveryStatus.SEND_FAILED }?.platformResultCode
                    "FALHOU — ${smsFailureReason(code)}"
                }
                parts.all { it.status == SmsDeliveryStatus.DELIVERED } -> "ENTREGUE AO APARELHO"
                parts.all { it.status == SmsDeliveryStatus.HANDED_TO_RADIO || it.status == SmsDeliveryStatus.DELIVERED } ->
                    "ENVIADO À OPERADORA"
                else -> "ENVIANDO..."
            }
            "$name: $status"
        }
        val waiting = (recipientCount - attempts.map { it.recipient }.distinct().size).coerceAtLeast(0)
        return buildString {
            appendLine("Envio direto, automático e individual:")
            if (rows.isEmpty()) appendLine("Preparando o primeiro contato...") else appendLine(rows.joinToString("\n"))
            if (waiting > 0) append("$waiting contato(s) aguardando na fila.")
            else append("A entrega final ainda depende da operadora e do aparelho de destino.")
        }
    }

    private fun smsFailureReason(code: Int?): String = when (code) {
        android.telephony.SmsManager.RESULT_ERROR_GENERIC_FAILURE ->
            "falha do chip/operadora; confira sinal, saldo e chip padrão de SMS"
        android.telephony.SmsManager.RESULT_ERROR_RADIO_OFF -> "rádio ou modo avião ativado"
        android.telephony.SmsManager.RESULT_ERROR_NO_SERVICE -> "sem sinal da operadora"
        android.telephony.SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "limite de SMS do aparelho ou operadora"
        android.telephony.SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> "número bloqueado pela lista fixa do SIM"
        android.telephony.SmsManager.RESULT_NETWORK_ERROR -> "erro de rede da operadora"
        android.telephony.SmsManager.RESULT_MODEM_ERROR -> "erro interno do modem/chip"
        SmsAlertDispatcher.RESULT_NO_DEFAULT_SUBSCRIPTION -> "nenhum chip padrão definido para SMS"
        SmsAlertDispatcher.RESULT_CALLBACK_TIMEOUT -> "o modem não respondeu dentro do prazo"
        SmsAlertDispatcher.RESULT_IMMEDIATE_EXCEPTION -> "o Android recusou o envio"
        else -> "falha da operadora/modem (código ${code ?: "desconhecido"})"
    }

    private fun showWhatsAppAutomationExplanation() {
        AlertDialog.Builder(this)
            .setTitle("WhatsApp automático")
            .setMessage("O WhatsApp pessoal não permite que outro aplicativo pressione Enviar sozinho. Para envio realmente automático a todos, o Estou Seguro precisa de um servidor integrado à API oficial do WhatsApp Business, com conta empresarial, consentimento dos contatos e modelo de mensagem aprovado. As credenciais nunca podem ficar dentro do APK.")
            .setPositiveButton("Entendi", null)
            .show()
    }

    private fun syncContactWithBackend(contact: TrustedContact) {
        if (!container.sandboxBackend.isEnabled) return
        val displayName = container.sessionRepository.displayName()
        container.cloudExecutor.execute {
            runCatching { container.sandboxBackend.syncContact(displayName, contact) }.fold(
                onSuccess = { result -> runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    result.consentUrl?.let { showConsentLink(contact.name, it) }
                } },
                onFailure = { error -> runOnUiThread {
                    if (!isFinishing && !isDestroyed) handleBackendFailure(
                        error = error,
                        retry = { syncContactWithBackend(contact) },
                        fallbackMessage = "Contato salvo no celular. A sincronizacao do WhatsApp ficou pendente.",
                    )
                } },
            )
        }
    }

    private fun removeContactFromBackend(localId: Long) {
        if (!container.sandboxBackend.isEnabled) return
        container.cloudExecutor.execute {
            runCatching { container.sandboxBackend.removeContact(localId) }
        }
    }

    private fun enqueueCloudAlert(prepared: PreparedAlert, category: BackendAlertCategory) {
        if (!container.sandboxBackend.isEnabled) return
        val displayName = container.sessionRepository.displayName()
        container.cloudExecutor.execute {
            runCatching { container.sandboxBackend.createAlert(displayName, prepared, category) }.fold(
                onSuccess = { result -> runOnUiThread {
                    if (!isFinishing && !isDestroyed) showCloudAlertResult(result)
                } },
                onFailure = { error -> runOnUiThread {
                    if (!isFinishing && !isDestroyed) handleBackendFailure(
                        error = error,
                        retry = { enqueueCloudAlert(prepared, category) },
                        fallbackMessage = "Canal WhatsApp oficial indisponivel. O envio local por SMS continuou normalmente.",
                    )
                } },
            )
        }
    }

    private fun showCloudAlertResult(result: CloudAlertResult) {
        when {
            result.authorizedRecipients > 0 -> Toast.makeText(
                this,
                "WhatsApp oficial: alerta entrou na fila para ${result.authorizedRecipients} contato(s) autorizado(s).",
                Toast.LENGTH_LONG,
            ).show()
            result.pendingConsentRecipients > 0 -> {
                Toast.makeText(
                    this,
                    "WhatsApp ainda sem destinatarios autorizados; o SMS local continuou ativo.",
                    Toast.LENGTH_LONG,
                ).show()
                result.pendingConsentUrls.firstOrNull()?.let { showConsentLink("seu contato", it) }
            }
        }
    }

    private fun showConsentLink(contactName: String, consentUrl: String) {
        AlertDialog.Builder(this)
            .setTitle("Autorizar WhatsApp oficial")
            .setMessage("Envie este link para $contactName. A pessoa precisa autorizar uma vez antes de receber alertas automaticos pelo WhatsApp Business. O SMS local nao depende dessa autorizacao.")
            .setNegativeButton("Depois", null)
            .setPositiveButton("Compartilhar link") { _, _ ->
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Autorize meus alertas de emergencia do Estou Seguro: $consentUrl")
                }
                startActivity(Intent.createChooser(share, "Enviar autorizacao"))
            }
            .show()
    }

    private fun handleBackendFailure(error: Throwable, retry: () -> Unit, fallbackMessage: String) {
        if (error is BackendActivationRequiredException) {
            showBackendActivationDialog(retry)
        } else {
            Toast.makeText(this, fallbackMessage, Toast.LENGTH_LONG).show()
        }
    }

    private fun showBackendActivationDialog(retry: () -> Unit) {
        pendingBackendRetries += retry
        if (backendActivationDialog?.isShowing == true) return

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(6), dp(22), 0)
        }
        val explanation = paragraph(
            "Digite o codigo temporario de 12 caracteres fornecido para este teste. Ele sera usado uma vez e nao sera salvo. O SMS continua funcionando mesmo sem ativacao.",
        )
        val code = input(
            "XXXX-XXXX-XXXX",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        ).apply {
            transformationMethod = PasswordTransformationMethod.getInstance()
            filters = arrayOf(InputFilter.LengthFilter(14))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            contentDescription = "Codigo temporario de ativacao, 12 caracteres"
        }
        var formatting = false
        code.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(value: Editable?) {
                if (formatting) return
                val formatted = SandboxBackendClient.formatActivationCode(value?.toString().orEmpty())
                if (formatted != value?.toString()) {
                    formatting = true
                    code.setText(formatted)
                    code.setSelection(formatted.length)
                    formatting = false
                }
            }
        })
        val feedback = TextView(this).apply {
            setTextColor(RED_DARK)
            textSize = 13f
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        }
        form.addView(explanation)
        form.addView(fieldLabel("CODIGO DE ATIVACAO"))
        form.addView(code)
        form.addView(feedback)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Ativar WhatsApp oficial")
            .setView(form)
            .setNegativeButton("Agora nao", null)
            .setPositiveButton("Ativar", null)
            .create()
        backendActivationDialog = dialog
        dialog.setOnDismissListener {
            backendActivationDialog = null
            pendingBackendRetries.clear()
            code.text.clear()
        }
        dialog.setOnShowListener {
            val activate = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            activate.setOnClickListener {
                val candidate = code.text.toString().toCharArray()
                if (SandboxBackendClient.normalizeActivationCode(candidate) == null) {
                    candidate.fill('\u0000')
                    feedback.text = "Confira os 12 caracteres do codigo. Letras I, L, O e U nao sao usadas."
                    feedback.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                feedback.visibility = View.GONE
                activate.isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                container.cloudExecutor.execute {
                    runCatching {
                        container.sandboxBackend.activate(container.sessionRepository.displayName(), candidate)
                    }.fold(
                        onSuccess = { runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            val retries = pendingBackendRetries.toList()
                            pendingBackendRetries.clear()
                            dialog.dismiss()
                            retries.forEach { it() }
                        } },
                        onFailure = { runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            code.text.clear()
                            feedback.text = "Codigo invalido, expirado ou ja utilizado. Solicite outro e tente novamente."
                            feedback.visibility = View.VISIBLE
                            feedback.announceForAccessibility(feedback.text)
                            activate.isEnabled = true
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                            code.requestFocus()
                        } },
                    )
                }
            }
        }
        dialog.show()
    }

    private fun EmergencyType.smsCategory(): SmsEmergencyCategory = when (this) {
        EmergencyType.GENERAL -> SmsEmergencyCategory.GENERAL
        EmergencyType.MEDICAL -> SmsEmergencyCategory.MEDICAL
        EmergencyType.SECURITY -> SmsEmergencyCategory.SECURITY
        EmergencyType.DOMESTIC_VIOLENCE -> SmsEmergencyCategory.DOMESTIC_VIOLENCE
        EmergencyType.CHILD_DANGER -> SmsEmergencyCategory.CHILD_DANGER
        EmergencyType.ANXIETY -> SmsEmergencyCategory.ANXIETY
    }

    private fun EmergencyType.backendCategory(): BackendAlertCategory = when (this) {
        EmergencyType.GENERAL -> BackendAlertCategory.GENERAL
        EmergencyType.MEDICAL -> BackendAlertCategory.MEDICAL
        EmergencyType.SECURITY -> BackendAlertCategory.SECURITY
        EmergencyType.DOMESTIC_VIOLENCE -> BackendAlertCategory.DOMESTIC_VIOLENCE
        EmergencyType.CHILD_DANGER -> BackendAlertCategory.CHILD_DANGER
        EmergencyType.ANXIETY -> BackendAlertCategory.ANXIETY
    }

    private fun showSmsFallback(prepared: PreparedAlert, reason: String) {
        AlertDialog.Builder(this)
            .setTitle("SMS direto indisponível")
            .setMessage("$reason Você pode abrir o aplicativo de mensagens e confirmar manualmente o envio.")
            .setNegativeButton("Cancelar") { _, _ -> pendingPreparedAlert = null }
            .setPositiveButton("Abrir mensagens") { _, _ ->
                pendingPreparedAlert = null
                ShareDispatcher.emergency(this, prepared.message, prepared.recipients)
            }
            .show()
    }

    private fun loadDocumentVault() {
        runIo(
            action = container.manageDocumentVault::list,
            onSuccess = ::showDocumentVault,
            onError = { showError("Não foi possível abrir o cofre protegido.") },
        )
    }

    private fun showDocumentVault(documents: List<IdentityDocument>) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(12))
            addView(paragraph("Acesso protegido pelo PIN. Números e fotos ficam criptografados somente neste aparelho e nunca são incluídos nos alertas."))
        }
        if (documents.isEmpty()) {
            content.addView(paragraph("Nenhum documento cadastrado."))
        } else {
            documents.forEach { document ->
                content.addView(documentVaultCard(document), blockParams(10))
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Cofre de documentos")
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("Fechar", null)
            .setPositiveButton("Adicionar documento", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                showDocumentEditor(null)
            }
        }
        dialog.show()
    }

    private fun documentVaultCard(document: IdentityDocument): View = card(compact = true).apply {
        addView(TextView(this@MainActivity).apply {
            text = document.displayType
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TEXT)
        })
        addView(TextView(this@MainActivity).apply {
            text = maskedDocumentNumber(document)
            textSize = 14f
            setTextColor(TEXT_MUTED)
            setPadding(0, dp(3), 0, dp(8))
        })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(smallActionButton("Abrir", NAVY) { showDocumentDetails(document) })
            addView(smallActionButton("Fotos", BLUE) { showDocumentPhotos(document) })
            addView(smallActionButton("Editar", NAVY) { showDocumentEditor(document) })
        })
    }

    private fun maskedDocumentNumber(document: IdentityDocument): String = when (document.type) {
        DocumentType.CPF -> BrazilianCpf.masked(document.number)
        else -> "•••• ${document.number.filterNot(Char::isWhitespace).takeLast(4)}"
    }

    private fun showDocumentDetails(document: IdentityDocument) {
        val details = buildString {
            appendLine("Tipo: ${document.displayType}")
            appendLine("Número: ${document.number}")
            if (document.issuer.isNotBlank()) appendLine("Órgão emissor: ${document.issuer}")
            if (document.expiryDateIso.isNotBlank()) {
                appendLine("Validade: ${BrazilianDate.isoToDisplay(document.expiryDateIso)}")
            }
            if (document.notes.isNotBlank()) appendLine("Observações: ${document.notes}")
            append("Fotos: ${if (document.hasFrontImage) "frente" else "sem frente"}; ${if (document.hasBackImage) "verso" else "sem verso"}")
        }
        AlertDialog.Builder(this)
            .setTitle(document.displayType)
            .setMessage(details)
            .setNegativeButton("Excluir", null)
            .setNeutralButton("Fotos") { _, _ -> showDocumentPhotos(document) }
            .setPositiveButton("Fechar", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(RED_DARK)
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                        AlertDialog.Builder(this)
                            .setTitle("Excluir documento?")
                            .setMessage("O cadastro e as fotos criptografadas serão apagados deste aparelho.")
                            .setNegativeButton("Cancelar", null)
                            .setPositiveButton("Excluir") { _, _ ->
                                runIo(
                                    action = { container.manageDocumentVault.delete(document.id) },
                                    onSuccess = { dialog.dismiss(); loadDocumentVault() },
                                    onError = { showError("Não foi possível excluir o documento.") },
                                )
                            }.show()
                    }
                }
                dialog.show()
            }
    }

    private fun showDocumentEditor(existing: IdentityDocument?) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        form.addView(paragraph("Cadastre apenas documentos seus. Use “Outro documento” para tipos não listados."))
        form.addView(fieldLabel("TIPO DE DOCUMENTO"))
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                DocumentType.entries.map { it.label },
            )
            setSelection(existing?.type?.ordinal ?: 0)
        }
        form.addView(spinner, blockParams(12, 54))

        fun field(label: String, hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
            val edit = input(hint, type).apply { setText(value) }
            form.addView(fieldLabel(label)); form.addView(edit)
            return edit
        }
        val customType = field("NOME DO TIPO (SE OUTRO)", "Ex.: carteira estudantil", existing?.customType.orEmpty())
        val number = field("NÚMERO DO DOCUMENTO", "Número ou registro", existing?.number.orEmpty())
        val issuer = field("ÓRGÃO EMISSOR", "Ex.: SSP/MG, DETRAN/MG", existing?.issuer.orEmpty())
        val expiry = field(
            "VALIDADE (OPCIONAL)", "DD/MM/AAAA",
            existing?.expiryDateIso?.takeIf(String::isNotBlank)?.let(BrazilianDate::isoToDisplay).orEmpty(),
            InputType.TYPE_CLASS_NUMBER,
        ).also(::configureBrazilianDateField)
        val notes = field("OBSERVAÇÕES", "Informações adicionais", existing?.notes.orEmpty())

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Adicionar documento" else "Editar documento")
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val type = DocumentType.entries[spinner.selectedItemPosition]
                val expiryIso = try {
                    BrazilianDate.displayToIso(expiry.text.toString())
                } catch (_: Exception) {
                    expiry.error = "Informe uma data válida no formato DD/MM/AAAA."
                    return@setOnClickListener
                }
                val candidate = IdentityDocument(
                    id = existing?.id.orEmpty(), type = type,
                    customType = customType.text.toString(), number = number.text.toString(),
                    issuer = issuer.text.toString(), expiryDateIso = expiryIso,
                    notes = notes.text.toString(),
                    hasFrontImage = existing?.hasFrontImage == true,
                    hasBackImage = existing?.hasBackImage == true,
                    updatedAtEpochMillis = existing?.updatedAtEpochMillis ?: 0,
                )
                runIo(
                    action = { container.manageDocumentVault.save(candidate) },
                    onSuccess = { saved ->
                        dialog.dismiss()
                        Toast.makeText(this, "Documento salvo no cofre criptografado.", Toast.LENGTH_LONG).show()
                        showDocumentPhotos(saved)
                    },
                    onError = { showError(it.message ?: "Revise os dados do documento.") },
                )
            }
        }
        dialog.show()
    }

    private fun showDocumentPhotos(document: IdentityDocument) {
        val options = arrayOf(
            "Tirar foto da frente", "Tirar foto do verso",
            "Escolher frente da galeria", "Escolher verso da galeria",
            "Ver foto da frente", "Ver foto do verso",
        )
        AlertDialog.Builder(this)
            .setTitle("Fotos — ${document.displayType}")
            .setItems(options) { _, index ->
                val side = if (index % 2 == 0) DocumentSide.FRONT else DocumentSide.BACK
                when (index) {
                    0, 1 -> startDocumentCapture(document, side)
                    2, 3 -> startDocumentPicker(document, side)
                    else -> showDocumentImage(document, side)
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun startDocumentCapture(document: IdentityDocument, side: DocumentSide) {
        val directory = File(cacheDir, "document-capture").apply { mkdirs() }
        val temporary = File.createTempFile("capture_", ".jpg", directory)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", temporary)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri("Foto do documento", uri)
        }
        pendingDocumentImage = PendingDocumentImage(document.id, side)
        pendingCaptureFile = temporary
        try {
            startActivityForResult(intent, DOCUMENT_CAMERA_REQUEST)
        } catch (_: Exception) {
            pendingDocumentImage = null
            pendingCaptureFile = null
            temporary.delete()
            showError("Nenhum aplicativo de câmera está disponível.")
        }
    }

    private fun startDocumentPicker(document: IdentityDocument, side: DocumentSide) {
        pendingDocumentImage = PendingDocumentImage(document.id, side)
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            },
            DOCUMENT_PICKER_REQUEST,
        )
    }

    private fun showDocumentImage(document: IdentityDocument, side: DocumentSide) {
        runIo(
            action = { container.manageDocumentVault.loadImage(document.id, side) },
            onSuccess = { bytes ->
                if (bytes == null) {
                    showError("Esta foto ainda não foi cadastrada.")
                } else {
                    val bitmap = decodePreview(bytes)
                    bytes.fill(0)
                    if (bitmap == null) showError("A imagem armazenada não pôde ser exibida.")
                    else AlertDialog.Builder(this)
                        .setTitle("${document.displayType} — ${if (side == DocumentSide.FRONT) "frente" else "verso"}")
                        .setView(ImageView(this).apply {
                            setImageBitmap(bitmap)
                            adjustViewBounds = true
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            setPadding(dp(8), dp(8), dp(8), dp(8))
                        })
                        .setPositiveButton("Fechar") { _, _ -> bitmap.recycle() }
                        .show()
                }
            },
            onError = { showError("Não foi possível abrir a foto protegida.") },
        )
    }

    private fun medicalSummary(profile: EmergencyMedicalProfile?): String {
        if (profile == null) return ""
        fun String.short() = trim().take(80)
        val items = buildList {
            profile.bloodType?.let { add("sangue ${bloodTypeLabel(it)}") }
            if (profile.allergies.isNotBlank()) add("alergias: ${profile.allergies.short()}")
            if (profile.medications.isNotBlank()) add("medicações: ${profile.medications.short()}")
            if (profile.medicalConditions.isNotBlank()) add("condições: ${profile.medicalConditions.short()}")
            if (profile.healthPlanProvider.isNotBlank()) {
                add("plano: ${profile.healthPlanProvider.short()} ${profile.healthPlanMemberId.short()}".trim())
            }
        }
        return if (items.isEmpty()) "" else " Ficha médica informada pela pessoa: ${items.joinToString("; ").take(360)}."
    }

    private fun loadMedicalProfile() {
        runIo(
            action = container.manageMedicalProfile::load,
            onSuccess = ::showMedicalProfileDialog,
            onError = { showError("Não foi possível abrir a ficha médica protegida.") },
        )
    }

    private fun showMedicalProfileDialog(existing: EmergencyMedicalProfile?) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        form.addView(paragraph("Preenchimento opcional. Os dados são criptografados neste aparelho. Não informe CPF: ele não é necessário para o primeiro atendimento."))

        fun field(label: String, hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
            val edit = input(hint, type).apply { setText(value) }
            form.addView(fieldLabel(label))
            form.addView(edit)
            return edit
        }

        val preferredName = field("NOME PREFERIDO", "Como deseja ser identificada", existing?.preferredName.orEmpty())
        val birthDate = field(
            "DATA DE NASCIMENTO",
            "DD/MM/AAAA",
            existing?.birthDateIso?.let { runCatching { BrazilianDate.isoToDisplay(it) }.getOrDefault("") }.orEmpty(),
            InputType.TYPE_CLASS_NUMBER,
        ).also(::configureBrazilianDateField)
        val bloodType = field("TIPO SANGUÍNEO", "Ex.: O+, A-, AB+", existing?.bloodType?.let(::bloodTypeLabel).orEmpty())
        val allergies = field("ALERGIAS", "Medicamentos, alimentos, látex...", existing?.allergies.orEmpty())
        val medications = field("MEDICAÇÕES", "Medicamentos de uso contínuo", existing?.medications.orEmpty())
        val conditions = field("CONDIÇÕES IMPORTANTES", "Diabetes, epilepsia, asma...", existing?.medicalConditions.orEmpty())
        val planProvider = field("PLANO DE SAÚDE", "Operadora", existing?.healthPlanProvider.orEmpty())
        val planId = field("NÚMERO DO BENEFICIÁRIO", "Número da carteirinha", existing?.healthPlanMemberId.orEmpty())
        val notes = field("OBSERVAÇÕES", "Orientações úteis em uma emergência", existing?.emergencyNotes.orEmpty())

        val scroll = ScrollView(this).apply { addView(form) }
        val builder = AlertDialog.Builder(this)
            .setTitle("Ficha médica de emergência")
            .setView(scroll)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar", null)
        if (existing != null) builder.setNeutralButton("Apagar", null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsedBloodType = parseBloodType(bloodType.text.toString())
                if (bloodType.text.isNotBlank() && parsedBloodType == null) {
                    bloodType.error = "Use A+, A-, B+, B-, AB+, AB-, O+ ou O-."
                    return@setOnClickListener
                }
                val birthDateIso = try {
                    BrazilianDate.displayToIso(birthDate.text.toString())
                } catch (_: Exception) {
                    birthDate.error = "Informe uma data válida no formato DD/MM/AAAA."
                    return@setOnClickListener
                }
                val candidate = EmergencyMedicalProfile(
                    preferredName = preferredName.text.toString(),
                    birthDateIso = birthDateIso,
                    bloodType = parsedBloodType,
                    allergies = allergies.text.toString(),
                    medications = medications.text.toString(),
                    medicalConditions = conditions.text.toString(),
                    healthPlanProvider = planProvider.text.toString(),
                    healthPlanMemberId = planId.text.toString(),
                    emergencyNotes = notes.text.toString(),
                    updatedAtEpochMillis = existing?.updatedAtEpochMillis ?: 0,
                )
                runIo(
                    action = { container.manageMedicalProfile.save(candidate) },
                    onSuccess = {
                        dialog.dismiss()
                        Toast.makeText(this, "Ficha médica salva com criptografia.", Toast.LENGTH_LONG).show()
                    },
                    onError = { showError(it.message ?: "Revise os dados informados.") },
                )
            }
            if (existing != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Apagar ficha médica?")
                        .setMessage("Os dados médicos armazenados neste aparelho serão removidos.")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Apagar") { _, _ ->
                            runIo(
                                action = container.manageMedicalProfile::delete,
                                onSuccess = { dialog.dismiss() },
                                onError = { showError("Não foi possível apagar a ficha.") },
                            )
                        }
                        .show()
                }
            }
        }
        dialog.show()
    }

    private fun parseBloodType(value: String): BloodType? = when (
        value.trim().uppercase().replace(" ", "")
    ) {
        "A+" -> BloodType.A_POSITIVE
        "A-" -> BloodType.A_NEGATIVE
        "B+" -> BloodType.B_POSITIVE
        "B-" -> BloodType.B_NEGATIVE
        "AB+" -> BloodType.AB_POSITIVE
        "AB-" -> BloodType.AB_NEGATIVE
        "O+" -> BloodType.O_POSITIVE
        "O-" -> BloodType.O_NEGATIVE
        else -> null
    }

    private fun bloodTypeLabel(value: BloodType): String = when (value) {
        BloodType.A_POSITIVE -> "A+"
        BloodType.A_NEGATIVE -> "A-"
        BloodType.B_POSITIVE -> "B+"
        BloodType.B_NEGATIVE -> "B-"
        BloodType.AB_POSITIVE -> "AB+"
        BloodType.AB_NEGATIVE -> "AB-"
        BloodType.O_POSITIVE -> "O+"
        BloodType.O_NEGATIVE -> "O-"
    }

    private fun performCheckIn() {
        if (currentSnapshot.contacts.isEmpty()) {
            showError("Cadastre ao menos um contato de confiança.")
            return
        }
        ShareDispatcher.checkIn(
            this,
            container.sessionRepository.displayName(),
            currentSnapshot.contacts,
        )
    }

    @Deprecated("Compatibilidade com captura e seleção de imagem no MVP nativo")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != DOCUMENT_CAMERA_REQUEST && requestCode != DOCUMENT_PICKER_REQUEST) return
        val target = pendingDocumentImage
        val capture = pendingCaptureFile
        pendingDocumentImage = null
        pendingCaptureFile = null
        if (resultCode != RESULT_OK || target == null) {
            capture?.delete()
            return
        }
        runIo(
            action = {
                val bytes = if (requestCode == DOCUMENT_CAMERA_REQUEST) {
                    requireNotNull(capture) { "Captura não encontrada." }
                    require(capture.length() in 1..MAX_DOCUMENT_IMAGE_BYTES.toLong()) { "A foto deve ter no máximo 12 MB." }
                    capture.readBytes()
                } else {
                    val uri = requireNotNull(data?.data) { "Imagem não selecionada." }
                    readImageBytes(uri)
                }
                try {
                    container.manageDocumentVault.saveImage(target.documentId, target.side, bytes, "image/jpeg")
                } finally {
                    bytes.fill(0)
                    capture?.delete()
                }
            },
            onSuccess = {
                Toast.makeText(this, "Foto protegida e salva no cofre.", Toast.LENGTH_LONG).show()
                loadDocumentVault()
            },
            onError = {
                capture?.delete()
                showError(it.message ?: "Não foi possível proteger a foto.")
            },
        )
    }

    private fun readImageBytes(uri: Uri): ByteArray {
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Não foi possível abrir a imagem." }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_DOCUMENT_IMAGE_BYTES) { "A imagem deve ter no máximo 12 MB." }
                output.write(buffer, 0, count)
            }
            buffer.fill(0)
            return output.toByteArray()
        }
    }

    private fun decodePreview(bytes: ByteArray): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) },
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_REQUEST && pendingEmergency) {
            pendingEmergency = false
            if (grantResults.none { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Continuando sem localização.", Toast.LENGTH_LONG).show()
            }
            prepareAndShareAlert()
        } else if (requestCode == SMS_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                dispatchPreparedAlert()
            } else {
                pendingPreparedAlert?.let {
                    showSmsFallback(it, "A permissão para enviar SMS não foi concedida.")
                }
            }
        }
    }

    private fun screenContainer() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(36))
        setBackgroundColor(SURFACE)
    }

    private fun wrap(content: View): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        setBackgroundColor(SURFACE)
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun brandHeader(titleValue: String, subtitleValue: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(24), dp(22), dp(24))
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(NAVY, NAVY_DARK),
        ).apply { cornerRadius = dp(24).toFloat() }
        elevation = dp(4).toFloat()
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.logo_app)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = null
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(9) })
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.brand_name)
                textSize = 12f
                letterSpacing = 0.16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(BLUE_ACCENT)
            })
        })
        addView(TextView(this@MainActivity).apply {
            text = titleValue
            textSize = 27f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, dp(10), 0, dp(4))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setAccessibilityHeading(true)
        })
        addView(TextView(this@MainActivity).apply {
            text = subtitleValue
            textSize = 15f
            setTextColor(Color.rgb(205, 224, 239))
        })
        layoutParams = blockParams(marginBottomDp = 14)
    }

    private fun card(compact: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            dp(if (compact) 16 else 20),
            dp(if (compact) 16 else 20),
            dp(if (compact) 16 else 20),
            dp(if (compact) 16 else 20),
        )
        background = rounded(Color.WHITE, 20)
        elevation = dp(2).toFloat()
    }

    private fun sectionHeader(titleValue: String, trailing: String?): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        setPadding(dp(2), dp(18), dp(2), dp(10))
        addView(TextView(this@MainActivity).apply {
            text = titleValue
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TEXT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setAccessibilityHeading(true)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        trailing?.let {
            addView(TextView(this@MainActivity).apply {
                text = it
                textSize = 12f
                setTextColor(TEXT_MUTED)
                background = rounded(BLUE_SOFT, 10)
                setPadding(dp(9), dp(4), dp(9), dp(4))
            })
        }
    }

    private fun overline(value: String) = TextView(this).apply {
        text = value
        textSize = 11f
        letterSpacing = 0.12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(BLUE)
        setPadding(0, 0, 0, dp(7))
    }

    private fun cardTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 21f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(TEXT)
        setPadding(0, 0, 0, dp(8))
    }

    private fun paragraph(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(TEXT_MUTED)
        setLineSpacing(0f, 1.18f)
        setPadding(0, 0, 0, dp(16))
    }

    private fun helperText(value: String) = TextView(this).apply {
        text = getString(R.string.info_with_value, value)
        textSize = 12f
        setTextColor(TEXT_MUTED)
        gravity = Gravity.CENTER
    }

    private fun fieldLabel(value: String) = TextView(this).apply {
        text = value
        textSize = 11f
        letterSpacing = 0.1f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(TEXT_MUTED)
        setPadding(dp(2), dp(4), 0, dp(6))
    }

    private fun input(hintValue: String, type: Int) = EditText(this).apply {
        hint = hintValue
        inputType = type
        textSize = 16f
        setTextColor(TEXT)
        setHintTextColor(TEXT_LIGHT)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(Color.rgb(250, 252, 255), 14, BORDER)
        layoutParams = blockParams(heightDp = 54, marginBottomDp = 12)
    }

    private fun configureBrazilianDateField(field: EditText) {
        field.filters = arrayOf(InputFilter.LengthFilter(10))
        field.contentDescription = "Data no formato dia, mês e ano"
        var editing = false
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(value: Editable?) {
                if (editing) return
                val masked = BrazilianDate.mask(value?.toString().orEmpty())
                if (masked != value?.toString()) {
                    editing = true
                    field.setText(masked)
                    field.setSelection(masked.length)
                    editing = false
                }
            }
        })
    }

    private fun pinEntry(hintValue: String): PinEntry {
        val input = EditText(this).apply {
            hint = hintValue
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            isSingleLine = true
            textSize = 16f
            setTextColor(TEXT)
            setHintTextColor(TEXT_LIGHT)
            setPadding(dp(14), 0, dp(6), 0)
            background = null
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
        }
        val toggle = ImageButton(this).apply {
            background = null
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        var visibilityState = PinVisibilityState.HIDDEN

        fun renderVisibilityState() {
            // The icon represents the current state. The accessible label and tooltip
            // describe the action that will run when the button is activated.
            input.transformationMethod = if (visibilityState.isVisible) {
                null
            } else {
                PasswordTransformationMethod.getInstance()
            }
            toggle.setImageResource(
                if (visibilityState.isVisible) R.drawable.ic_visibility else R.drawable.ic_visibility_off,
            )
            toggle.contentDescription = getString(
                if (visibilityState.isVisible) R.string.hide_pin else R.string.show_pin,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                toggle.stateDescription = getString(
                    if (visibilityState.isVisible) R.string.pin_visible else R.string.pin_hidden,
                )
            }
            toggle.tooltipText = toggle.contentDescription
        }

        toggle.setOnClickListener {
            visibilityState = visibilityState.toggled()
            renderVisibilityState()
            input.setSelection(input.text.length)
            input.requestFocus()
        }
        // Do not rely only on inputType: explicitly render the safe initial state.
        renderVisibilityState()
        val field = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.rgb(250, 252, 255), 14, BORDER)
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(toggle, LinearLayout.LayoutParams(dp(48), dp(48)))
            layoutParams = blockParams(heightDp = 54, marginBottomDp = 12)
        }
        return PinEntry(field, input)
    }

    private fun hideKeyboard(view: View) {
        view.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun primaryButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 16f
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = rounded(NAVY, 16)
        elevation = dp(2).toFloat()
        setOnClickListener { onClick() }
        layoutParams = blockParams(heightDp = 56, marginBottomDp = 2)
    }

    private fun outlineButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 15f
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(NAVY)
        background = rounded(Color.TRANSPARENT, 16, NAVY)
        setOnClickListener { onClick() }
        layoutParams = blockParams(heightDp = 54, marginBottomDp = 4)
    }

    private fun smallActionButton(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        minHeight = 0
        minimumHeight = 0
        setTextColor(color)
        background = rounded(Color.TRANSPARENT, 12, BORDER)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply {
            marginStart = dp(8)
        }
    }

    private fun privacyNote() = TextView(this).apply {
        text = getString(R.string.local_data_notice)
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(TEXT_MUTED)
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun brandSpotlight(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(18), dp(18), dp(18), dp(10))
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.logo_app)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Logo Estou Seguro: proteção e localização"
        }, LinearLayout.LayoutParams(dp(150), dp(150)))
        addView(TextView(this@MainActivity).apply {
            text = getString(R.string.brand_promise)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(NAVY)
        })
        addView(TextView(this@MainActivity).apply {
            text = getString(R.string.brand_network)
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(TEXT_MUTED)
            setPadding(0, dp(4), 0, 0)
        })
    }

    private fun legalNotice() = TextView(this).apply {
        text = getString(R.string.mvp_legal_notice)
        textSize = 11f
        gravity = Gravity.CENTER
        setTextColor(TEXT_LIGHT)
        setLineSpacing(0f, 1.15f)
        setPadding(dp(12), dp(22), dp(12), 0)
    }

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        strokeColor?.let { setStroke(dp(1), it) }
    }

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun blockParams(
        marginBottomDp: Int = 14,
        heightDp: Int? = null,
    ) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        heightDp?.let(::dp) ?: ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        bottomMargin = dp(marginBottomDp)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun <T> runIo(action: () -> T, onSuccess: (T) -> Unit, onError: (Throwable) -> Unit) {
        container.ioExecutor.execute {
            runCatching(action).fold(
                onSuccess = { value -> runOnUiThread {
                    if (!isFinishing && !isDestroyed) onSuccess(value)
                } },
                onFailure = { error -> runOnUiThread {
                    if (!isFinishing && !isDestroyed) onError(error)
                } },
            )
        }
    }

    private data class PinEntry(
        val view: View,
        val input: EditText,
    )

    private data class PendingDocumentImage(
        val documentId: String,
        val side: DocumentSide,
    )

    private enum class EmergencyType(
        val confirmationTitle: String,
        val confirmationMessage: String,
        val messagePrefix: String,
    ) {
        GENERAL(
            "Preparar alerta SOS?",
            "Sua rede de confiança será avisada de que você precisa de ajuda.",
            "SOS URGENTE — ",
        ),
        MEDICAL(
            "Alerta médico?",
            "Sua rede será avisada sobre uma possível emergência médica.",
            "EMERGÊNCIA MÉDICA — ",
        ),
        SECURITY(
            "Alerta de risco?",
            "Sua rede será avisada sobre uma situação de roubo, sequestro ou ameaça.",
            "RISCO DE SEGURANÇA — ",
        ),
        DOMESTIC_VIOLENCE(
            "Pedir ajuda contra violência?",
            "Sua rede será avisada sobre uma situação de agressão, abuso, ameaça ou violência psicológica. Em perigo imediato, ligue também para 190.",
            "VIOLÊNCIA OU AMEAÇA CONTRA MULHER — PRECISO DE AJUDA AGORA. ",
        ),
        CHILD_DANGER(
            "Pedir ajuda para uma criança?",
            "Sua rede será avisada de que uma criança ou adolescente pode estar em risco. Em perigo imediato, ligue também para 190.",
            "CRIANÇA OU ADOLESCENTE EM RISCO — AJUDA URGENTE. ",
        ),
        ANXIETY(
            "Pedir apoio agora?",
            "Sua rede será avisada de que você está em crise e precisa de apoio.",
            "PRECISO DE APOIO AGORA — ",
        ),
    }

    companion object {
        private const val LOCATION_REQUEST = 41
        private const val SMS_REQUEST = 42
        private const val DOCUMENT_CAMERA_REQUEST = 43
        private const val DOCUMENT_PICKER_REQUEST = 44
        private const val MAX_DOCUMENT_IMAGE_BYTES = 12 * 1024 * 1024
        private const val EXTRA_OPEN_EMERGENCY = "open_emergency"

        private val NAVY_DARK = Color.rgb(9, 30, 53)
        private val NAVY = Color.rgb(16, 53, 82)
        private val BLUE = Color.rgb(32, 108, 162)
        private val BLUE_ACCENT = Color.rgb(123, 208, 255)
        private val BLUE_SOFT = Color.rgb(231, 244, 252)
        private val SURFACE = Color.rgb(244, 247, 250)
        private val TEXT = Color.rgb(23, 39, 54)
        private val TEXT_MUTED = Color.rgb(91, 108, 123)
        private val TEXT_LIGHT = Color.rgb(139, 153, 166)
        private val BORDER = Color.rgb(218, 226, 233)
        private val RED = Color.rgb(210, 51, 45)
        private val RED_DARK = Color.rgb(154, 28, 28)
        private val GREEN = Color.rgb(24, 128, 84)
        private val GREEN_DARK = Color.rgb(18, 91, 63)
        private val GREEN_SOFT = Color.rgb(226, 247, 237)
        private val AMBER = Color.rgb(192, 112, 8)
        private val AMBER_DARK = Color.rgb(132, 76, 3)
        private val AMBER_SOFT = Color.rgb(255, 243, 214)
    }
}

internal enum class PinVisibilityState(val isVisible: Boolean) {
    HIDDEN(false),
    VISIBLE(true);

    fun toggled(): PinVisibilityState = if (this == HIDDEN) VISIBLE else HIDDEN
}
