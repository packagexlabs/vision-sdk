package io.packagex.visiondemo.data

data class Secrets(val apiKey: String, val environment: String) {
    val isMissing get() = apiKey.isBlank()
    val missingMessage get() = "Add ${environment.uppercase()}_API_KEY to secrets.properties"

    companion object {
        fun pick(env: String, staging: String, production: String) =
            Secrets(if (env == "production") production else staging, env)
    }
}
