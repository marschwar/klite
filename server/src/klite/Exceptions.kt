package klite

import java.lang.System.Logger.Level.ERROR
import kotlin.reflect.KClass

open class StatusCodeException(val statusCode: Int, content: String?): Exception(content)
class NotFoundException(content: String?): StatusCodeException(404, content)
class ForbiddenException(content: String?): StatusCodeException(403, content)
class UnauthorizedException(content: String?): StatusCodeException(401, content)
class UnsupportedMediaTypeException(contentType: String?): StatusCodeException(415, "Unsupported Content-Type: $contentType")
class NotAcceptableException(contentType: String?): StatusCodeException(406, "Unsupported Accept: $contentType")
