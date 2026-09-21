package com.playmation.motionlabsbackend.web

import com.playmation.motionlabsbackend.catalog.Declaration
import com.playmation.motionlabsbackend.common.ApiError
import com.playmation.motionlabsbackend.common.ApiErrorResponse
import com.playmation.motionlabsbackend.common.PortalException
import com.playmation.motionlabsbackend.config.PortalProperties
import com.playmation.motionlabsbackend.format.AwclipSchema
import com.playmation.motionlabsbackend.system.SystemSettingsService
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.support.MissingServletRequestPartException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * Was die Workbench beim Start und vor jedem Upload wissen muss - erreichbar
 * auch bei abgeschalteter Community (Kill Switch).
 */
@RestController
@RequestMapping("/api/v1")
class StatusController(
    private val settings: SystemSettingsService,
    private val portal: PortalProperties,
    private val build: BuildStamp,
    /** Ohne echte Discord-App steht hier die Vorgabe aus der application.yaml. */
    @Value("\${spring.security.oauth2.client.registration.discord.client-id:unset}")
    private val discordClientId: String,
) {

    data class LicenseInfo(val id: String, val name: String, val summary: String, val url: String)

    data class StatusResponse(
        val communityEnabled: Boolean,
        val uploadsEnabled: Boolean,
        val formatVersion: Int,
        val declarationText: String,
        val declarationVersion: Int,
        val licenses: List<LicenseInfo>,
        /** Ohne konfigurierte App fuehrt der Discord-Knopf nur zu Discords Fehlerseite. */
        val discordSignIn: Boolean,
        val devLogin: Boolean,
        /** Welcher Stand antwortet hier - siehe [BuildStamp]. */
        val build: BuildInfo,
    )

    data class BuildInfo(val number: String, val commit: String, val time: String)

    @GetMapping("/status")
    fun status() = StatusResponse(
        settings.communityEnabled(), settings.uploadsEnabled(), AwclipSchema.FORMAT_VERSION,
        Declaration.TEXT, Declaration.VERSION, licenses(),
        discordSignIn = discordClientId.isNotBlank() && discordClientId != "unset",
        devLogin = portal.devLogin,
        build = BuildInfo(build.number, build.commit, build.time),
    )

    /**
     * Zwei Möglichkeiten, nicht fünf Lizenzen: teilen oder nicht. Wer eine
     * Animation lädt, will sie benutzen und ändern dürfen - dafür reicht CC BY,
     * und alles andere war beim Hochladen nur eine Hürde.
     */
    @GetMapping("/licenses")
    fun licenses() = listOf(
        LicenseInfo(
            AwclipSchema.LICENSE_PUBLIC, "CC BY 4.0",
            "Public. Anyone may use and change it, also commercially, if they credit you.",
            "https://creativecommons.org/licenses/by/4.0/",
        ),
        LicenseInfo(
            AwclipSchema.LICENSE_PRIVATE, "Private",
            "Not listed and not searchable. Only reachable with the link, and nobody gets any rights.",
            "",
        ),
    ).also { list -> check(list.map { it.id } == AwclipSchema.LICENSES) }
}

@RestControllerAdvice
class ApiExceptionHandler(private val shell: ShellModel) {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun error(status: HttpStatus, code: String, message: String) =
        ResponseEntity.status(status).body(ApiErrorResponse(ApiError(code, message)))

    @ExceptionHandler(PortalException::class)
    fun portal(ex: PortalException) = error(ex.status, ex.code, ex.message ?: ex.code)

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun tooLarge(ex: MaxUploadSizeExceededException) = error(HttpStatus.PAYLOAD_TOO_LARGE, "too-large", "The file is too large.")

    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MissingServletRequestParameterException::class,
        MissingServletRequestPartException::class,
        MethodArgumentTypeMismatchException::class,
    )
    fun badRequest(ex: Exception) = error(HttpStatus.BAD_REQUEST, "invalid-request", "The request is malformed.")

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun method(ex: HttpRequestMethodNotSupportedException) = error(HttpStatus.METHOD_NOT_ALLOWED, "method-not-allowed", "Method not allowed.")

    /**
     * Eine Adresse, die es nicht gibt.
     *
     * Bis hierher bekam JEDER dafuer rohes JSON zu sehen - auch jemand, der in
     * Discord einem alten Link folgt und einen Browser vor sich hat.
     * `{"error":{"code":"not-found"}}` ist fuer einen Menschen keine Auskunft,
     * sondern der Eindruck, hier sei etwas kaputt.
     *
     * Die Unterscheidung laeuft ueber den Pfad, nicht ueber den Accept-Kopf:
     * unter `/api/` liegt die Schnittstelle, alles andere ist eine Seite. Das
     * ist auch dann richtig, wenn ein Werkzeug ohne Accept-Kopf anfragt.
     */
    @ExceptionHandler(NoResourceFoundException::class)
    fun notFound(ex: NoResourceFoundException, request: HttpServletRequest): Any {
        if (request.requestURI.startsWith("/api/"))
            return error(HttpStatus.NOT_FOUND, "not-found", "Not found.")

        return ModelAndView("notfound").apply {
            status = HttpStatus.NOT_FOUND
            shell.fill(model, SecurityContextHolder.getContext().authentication)
        }
    }

    @ExceptionHandler(Exception::class)
    fun unexpected(ex: Exception): ResponseEntity<ApiErrorResponse> {
        log.error("Unexpected error", ex)
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "Something went wrong on our side.")
    }
}
