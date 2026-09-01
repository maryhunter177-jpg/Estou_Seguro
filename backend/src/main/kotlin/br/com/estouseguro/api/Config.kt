package br.com.estouseguro.api

data class AppConfig(
    val environment: String,
    val port: Int,
    val publicBaseUrl: String,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val deviceTokenPepper: String,
    val consentTokenPepper: String,
    val sandboxRegistrationKey: String,
    val meta: MetaConfig,
    val workerEnabled: Boolean,
) {
    val isProduction: Boolean get() = environment == "production"

    fun validate() {
        require(environment in setOf("sandbox", "production")) { "APP_ENV must be sandbox or production" }
        require(port in 1..65535)
        require(publicBaseUrl.startsWith("https://") || !isProduction) { "Production requires HTTPS PUBLIC_BASE_URL" }
        require(deviceTokenPepper.length >= 32) { "DEVICE_TOKEN_PEPPER must have at least 32 characters" }
        require(consentTokenPepper.length >= 32) { "CONSENT_TOKEN_PEPPER must have at least 32 characters" }
        if (!isProduction) require(sandboxRegistrationKey.length >= 24) { "SANDBOX_REGISTRATION_KEY must have at least 24 characters" }
        if (workerEnabled || isProduction) meta.validate()
    }

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): AppConfig {
            fun required(name: String) = env[name]?.takeIf(String::isNotBlank)
                ?: error("Missing required environment variable: $name")
            val config = AppConfig(
                environment = env["APP_ENV"] ?: "sandbox",
                port = (env["PORT"] ?: "8080").toInt(),
                publicBaseUrl = required("PUBLIC_BASE_URL").trimEnd('/'),
                databaseUrl = required("DATABASE_URL"),
                databaseUser = required("DATABASE_USER"),
                databasePassword = required("DATABASE_PASSWORD"),
                deviceTokenPepper = required("DEVICE_TOKEN_PEPPER"),
                consentTokenPepper = required("CONSENT_TOKEN_PEPPER"),
                sandboxRegistrationKey = env["SANDBOX_REGISTRATION_KEY"].orEmpty(),
                meta = MetaConfig(
                    graphVersion = env["META_GRAPH_VERSION"] ?: "v26.0",
                    phoneNumberId = env["META_PHONE_NUMBER_ID"].orEmpty(),
                    accessToken = env["META_ACCESS_TOKEN"].orEmpty(),
                    appSecret = env["META_APP_SECRET"].orEmpty(),
                    webhookVerifyToken = required("META_WEBHOOK_VERIFY_TOKEN"),
                    templateName = env["META_TEMPLATE_NAME"] ?: "alerta_emergencia",
                    templateLanguage = env["META_TEMPLATE_LANGUAGE"] ?: "pt_BR",
                ),
                workerEnabled = env["WORKER_ENABLED"]?.toBooleanStrictOrNull() ?: false,
            )
            config.validate()
            return config
        }
    }
}

data class MetaConfig(
    val graphVersion: String,
    val phoneNumberId: String,
    val accessToken: String,
    val appSecret: String,
    val webhookVerifyToken: String,
    val templateName: String,
    val templateLanguage: String,
) {
    fun validate() {
        require(graphVersion.matches(Regex("v\\d{1,2}\\.0"))) { "Invalid META_GRAPH_VERSION" }
        require(phoneNumberId.isNotBlank()) { "META_PHONE_NUMBER_ID is required" }
        require(accessToken.isNotBlank()) { "META_ACCESS_TOKEN is required" }
        require(appSecret.length >= 16) { "META_APP_SECRET is invalid" }
        require(templateName.matches(Regex("[a-z0-9_]{1,512}"))) { "Invalid template name" }
    }
}
