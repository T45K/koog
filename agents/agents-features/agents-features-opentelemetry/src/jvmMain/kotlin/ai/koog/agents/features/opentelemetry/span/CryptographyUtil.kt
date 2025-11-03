package ai.koog.agents.features.opentelemetry.span

import io.ktor.utils.io.core.toByteArray
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal object CryptographyUtil {

    internal fun String.sha256base64(): String {
        val hash = this.toByteArray().sha256()

        @OptIn(ExperimentalEncodingApi::class)
        return Base64.UrlSafe.encode(hash)
    }

    internal fun ByteArray.sha256(): ByteArray =
        MessageDigest
            .getInstance("SHA-256")
            .digest(this)
}
