package ar.edu.austral.inf.sd.server.model

import java.util.UUID

data class RegisteredNode(
    val name : String?,
    val host : String,
    val port : Int,
    val uuid : UUID,
    val salt : String,
    val lastRegisterResponse: RegisterResponse
)
