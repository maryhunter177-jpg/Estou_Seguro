package br.com.estouseguro

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.KeyEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import br.com.estouseguro.domain.model.BloodType
import br.com.estouseguro.domain.model.BrazilianPhoneNumber
import br.com.estouseguro.domain.model.DashboardSnapshot
import br.com.estouseguro.domain.model.EmergencyMedicalProfile
import br.com.estouseguro.domain.model.SmsDeliveryAttempt
import br.com.estouseguro.domain.model.SmsDeliveryStatus
import br.com.estouseguro.domain.model.TrustedContact
import br.com.estouseguro.domain.usecase.PreparedAlert
import br.com.estouseguro.platform.ShareDispatcher
import br.com.estouseguro.platform.SmsDispatchResult
import java.text.DateFormat
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
            setTextColor(if (type == EmergencyType.SECURITY) RED_DARK else NAVY)
            background = rounded(if (type == EmergencyType.SECURITY) Color.rgb(255, 237, 235) else BLUE_SOFT, 11)
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
                    onSuccess = { dialog.dismiss(); showDashboard() },
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
                    onSuccess = { showDashboard() },
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
                    prepared.message,
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
                val failed = result.immediateFailures.size
                val message = if (failed == 0) {
                    "Fila sequencial iniciada para ${result.recipientCount} contato(s). Cada SMS será liberado separadamente após a resposta do modem. A entrega depende da operadora."
                } else {
                    "A fila sequencial foi iniciada, mas $failed contato(s) têm número inválido ou falharam imediatamente. Revise os contatos destacados."
                }
                val whatsappContact = prepared.recipients.firstOrNull {
                    BrazilianPhoneNumber.normalizeForSms(it.phone) != null
                }
                AlertDialog.Builder(this)
                    .setTitle("Status do alerta")
                    .setMessage(message)
                    .setNeutralButton(
                        whatsappContact?.let { "WhatsApp: ${it.name.take(16)}" } ?: "WhatsApp/outro app",
                    ) { _, _ ->
                        if (whatsappContact == null) ShareDispatcher.whatsApp(this, prepared.message)
                        else ShareDispatcher.whatsApp(this, prepared.message, whatsappContact)
                    }
                    .setPositiveButton("Entendi") { _, _ -> showDashboard() }
                    .show()
            }
            SmsDispatchResult.NoRecipients -> showSmsFallback(
                prepared,
                "Nenhum telefone válido foi encontrado. Celular brasileiro precisa de DDD + 9 dígitos.",
            )
            SmsDispatchResult.UnsupportedDevice -> showSmsFallback(prepared, "Este aparelho não oferece envio direto de SMS.")
        }
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
        val birthDate = field("DATA DE NASCIMENTO", "AAAA-MM-DD", existing?.birthDateIso.orEmpty(), InputType.TYPE_CLASS_DATETIME)
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
                val candidate = EmergencyMedicalProfile(
                    preferredName = preferredName.text.toString(),
                    birthDateIso = birthDate.text.toString(),
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
        ANXIETY(
            "Pedir apoio agora?",
            "Sua rede será avisada de que você está em crise e precisa de apoio.",
            "PRECISO DE APOIO AGORA — ",
        ),
    }

    companion object {
        private const val LOCATION_REQUEST = 41
        private const val SMS_REQUEST = 42
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
