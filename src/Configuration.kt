package io.github.hemimogph

private const val ENVIRONMENT_PREFIX = "HERMIMORPH_BILL_"

data class ServerSettings(
    val host: String,
    val port: Int,
    val apiToken: String,
) {
    companion object {
        fun fromEnvironment(environment: (String) -> String? = System::getenv): ServerSettings {
            val portVariable = "${ENVIRONMENT_PREFIX}PORT"
            val port = environment.required("PORT").toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?: error("Environment variable $portVariable must be an integer between 1 and 65535")

            return ServerSettings(
                host = environment.required("HOST"),
                port = port,
                apiToken = environment.required("API_TOKEN"),
            )
        }
    }
}

internal fun ((String) -> String?).required(name: String): String {
    val variable = "$ENVIRONMENT_PREFIX$name"
    return invoke(variable)?.takeIf(String::isNotBlank)
        ?: error("Required environment variable $variable is missing or blank")
}
