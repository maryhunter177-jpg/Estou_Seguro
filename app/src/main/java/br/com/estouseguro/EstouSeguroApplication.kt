package br.com.estouseguro

import android.app.Application
import br.com.estouseguro.data.local.EstouSeguroDatabase
import br.com.estouseguro.data.repository.SecureSessionRepository
import br.com.estouseguro.data.repository.KeystoreMedicalProfileRepository
import br.com.estouseguro.data.repository.SqliteAlertRepository
import br.com.estouseguro.data.repository.SqliteContactRepository
import br.com.estouseguro.data.repository.SqliteSmsDeliveryRepository
import br.com.estouseguro.domain.usecase.LoadDashboard
import br.com.estouseguro.domain.usecase.LoadSmsDeliveryStatus
import br.com.estouseguro.domain.usecase.ManageContacts
import br.com.estouseguro.domain.usecase.ManageMedicalProfile
import br.com.estouseguro.domain.usecase.PrepareEmergencyAlert
import br.com.estouseguro.platform.AndroidLocationProvider
import br.com.estouseguro.platform.SmsAlertDispatcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class EstouSeguroApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
    private val database = EstouSeguroDatabase(application)
    private val contactRepository = SqliteContactRepository(database)
    private val alertRepository = SqliteAlertRepository(database)
    private val smsDeliveryRepository = SqliteSmsDeliveryRepository(database)

    val sessionRepository = SecureSessionRepository(application)
    val manageContacts = ManageContacts(contactRepository)
    val manageMedicalProfile = ManageMedicalProfile(KeystoreMedicalProfileRepository(application))
    val loadDashboard = LoadDashboard(contactRepository, alertRepository)
    val loadSmsDeliveryStatus = LoadSmsDeliveryStatus(smsDeliveryRepository)
    val prepareEmergencyAlert = PrepareEmergencyAlert(contactRepository, alertRepository)
    val smsAlertDispatcher = SmsAlertDispatcher(application, smsDeliveryRepository)
    val locationProvider = AndroidLocationProvider(application)
    val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
}
