package ar.edu.austral.inf.sd

import ar.edu.austral.inf.sd.server.api.PlayApiService
import ar.edu.austral.inf.sd.server.api.RegisterNodeApiService
import ar.edu.austral.inf.sd.server.api.RelayApiService
import ar.edu.austral.inf.sd.server.api.BadRequestException
import ar.edu.austral.inf.sd.server.api.ReconfigureApiService
import ar.edu.austral.inf.sd.server.api.UnregisterNodeApiService
import ar.edu.austral.inf.sd.server.model.PlayResponse
import ar.edu.austral.inf.sd.server.model.RegisterResponse
import ar.edu.austral.inf.sd.server.model.RegisteredNode
import ar.edu.austral.inf.sd.server.model.Signature
import ar.edu.austral.inf.sd.server.model.Signatures
import jakarta.validation.ConstraintViolation
import jakarta.validation.ConstraintViolationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.CountDownLatch
import kotlin.random.Random

@Component
class ApiServicesImpl : RegisterNodeApiService, RelayApiService, PlayApiService, UnregisterNodeApiService,
    ReconfigureApiService {

    @Value("\${server.name:nada}")
    private val myServerName: String = ""

    @Value("\${server.port:8080}")
    private val myServerPort: Int = 0
    private val nodes: MutableList<RegisteredNode> = mutableListOf()
    private var nextNode: RegisterResponse? = null
    private val messageDigest = MessageDigest.getInstance("SHA-512")
    private val salt = Base64.getEncoder().encodeToString(Random.nextBytes(9))
    private val currentRequest
        get() = (RequestContextHolder.getRequestAttributes() as ServletRequestAttributes).request
    private var resultReady = CountDownLatch(1)
    private var currentMessageWaiting = MutableStateFlow<PlayResponse?>(null)
    private var currentMessageResponse = MutableStateFlow<PlayResponse?>(null)
    private var xGameTimestamp: Int = 0

    @Value("DefaultTimeOut")
    private var timeout : Int = 0

    override fun registerNode(host: String?, port: Int?, uuid: UUID?, salt: String?, name: String?): RegisterResponse {
        //TODO: central node should be in the list, I dont think so, in consequence I remove it but check in the interface

        //TODO: find the correct way to check for violations, in general spring use it by it self, but I am getting optionals here
        //data
        val violations = validationViolationsFromRegisterConstrains(host, port, uuid, salt, name)
        if (!violations.isEmpty())
            throw ConstraintViolationException(
                "Parameter constraints violated",
                listOf<ConstraintViolation<RegisteredNode>>().toSet()
            )

        return when (checkForRegistryValidation(uuid!!, salt!!)) {
            RegistryValidation.Valid -> {
                val response : RegisterResponse = buildRegisterResponse();
                nodes.add(RegisteredNode(name, host!!, port!!, uuid, salt, response))

                response
            }
            RegistryValidation.Invalid ->  throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized access")
            RegistryValidation.Exists -> {
                val node = nodes.find { n -> n.uuid == uuid }
                //TODO: add code 202 to response
                node!!.lastRegisterResponse
            }
        }

    }

    private fun validationViolationsFromRegisterConstrains(
        host: String?, port: Int?, uuid: UUID?, salt: String?, name: String?
    ) : List<String> {
        val violations = mutableListOf<String>()
        if (host == null) violations.add("Host must be defined")
        if (port == null) violations.add("Port must be defined")
        if (uuid == null) violations.add("UUID must be defined")
        if (salt == null) violations.add("Salt must be defined")
        if (name == null) violations.add("Name must be defined")
        return violations
    }

    private fun checkForRegistryValidation(uuid: UUID, salt: String) : RegistryValidation {
        val node = nodes.find { n -> n.uuid == uuid }
        return if (node == null) RegistryValidation.Valid
        else if (node.salt == salt) RegistryValidation.Exists
        else RegistryValidation.Invalid
    }

    private enum class RegistryValidation {
        Valid, Invalid, Exists
    }

    private fun buildRegisterResponse() =
        if (nodes.isEmpty()) RegisterResponse(myServerName, myServerPort, timeout, xGameTimestamp)
        else {
            val lastNode = nodes.last();
            RegisterResponse(lastNode.host, lastNode.port, timeout, xGameTimestamp)
        }

    override fun relayMessage(message: String, signatures: Signatures, xGameTimestamp: Int?): Signature {
        val receivedHash = doHash(message.encodeToByteArray(), salt)
        val receivedContentType = currentRequest.getPart("message")?.contentType ?: "nada"
        val receivedLength = message.length
        if (nextNode != null) {
            // Soy un relé. busco el siguiente y lo mando
            // @ToDo do some work here
        } else {
            // me llego algo, no lo tengo que pasar
            if (currentMessageWaiting.value == null) throw BadRequestException("no waiting message")
            val current = currentMessageWaiting.getAndUpdate { null }!!
            val response = current.copy(
                contentResult = if (receivedHash == current.originalHash) "Success" else "Failure",
                receivedHash = receivedHash,
                receivedLength = receivedLength,
                receivedContentType = receivedContentType,
                signatures = signatures
            )
            currentMessageResponse.update { response }
            resultReady.countDown()
        }
        return Signature(
            name = myServerName,
            hash = receivedHash,
            contentType = receivedContentType,
            contentLength = receivedLength
        )
    }

    override fun sendMessage(body: String): PlayResponse {
        if (nodes.isEmpty()) {
            // inicializamos el primer nodo como yo mismo
            val me = RegisterResponse(currentRequest.serverName, myServerPort, "", "")
            nodes.add(me)
        }
        currentMessageWaiting.update { newResponse(body) }
        val contentType = currentRequest.contentType
        sendRelayMessage(body, contentType, nodes.last(), Signatures(listOf()))
        resultReady.await()
        resultReady = CountDownLatch(1)
        return currentMessageResponse.value!!
    }

    override fun unregisterNode(uuid: UUID?, salt: String?): String {
        TODO("Not yet implemented")
    }

    override fun reconfigure(
        uuid: UUID?,
        salt: String?,
        nextHost: String?,
        nextPort: Int?,
        xGameTimestamp: Int?
    ): String {
        TODO("Not yet implemented")
    }

    internal fun registerToServer(registerHost: String, registerPort: Int) {
        // @ToDo acá tienen que trabajar ustedes
        val registerNodeResponse: RegisterResponse = RegisterResponse("", -1, "", "")
        println("nextNode = ${registerNodeResponse}")
        nextNode = with(registerNodeResponse) { RegisterResponse(nextHost, nextPort, uuid, hash) }
    }

    private fun sendRelayMessage(
        body: String,
        contentType: String,
        relayNode: RegisterResponse,
        signatures: Signatures
    ) {
        // @ToDo acá tienen que trabajar ustedes
    }

    private fun clientSign(message: String, contentType: String): Signature {
        val receivedHash = doHash(message.encodeToByteArray(), salt)
        return Signature(myServerName, receivedHash, contentType, message.length)
    }

    private fun newResponse(body: String) = PlayResponse(
        "Unknown",
        currentRequest.contentType,
        body.length,
        doHash(body.encodeToByteArray(), salt),
        "Unknown",
        -1,
        "N/A",
        Signatures(listOf())
    )

    private fun doHash(body: ByteArray, salt: String): String {
        val saltBytes = Base64.getDecoder().decode(salt)
        messageDigest.update(saltBytes)
        val digest = messageDigest.digest(body)
        return Base64.getEncoder().encodeToString(digest)
    }

    companion object {
        fun newSalt(): String = Base64.getEncoder().encodeToString(Random.nextBytes(9))
    }
}
