package com.babasama.edendale.introdb

data class IntroDbResponse(
    val statusCode: Int,
    val body: String?,
    val headers: Map<String, String> = emptyMap(),
) {
    fun getHeader(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

fun interface IntroDbTransport {
    suspend fun execute(urlString: String): IntroDbResponse
}
