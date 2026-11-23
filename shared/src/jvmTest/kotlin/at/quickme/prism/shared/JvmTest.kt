package at.quickme.prism.shared

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.runBlocking

// The JVM's runner for commonTest (shared/default.nix: check): the JDK's
// HTTP server on loopback for the Ktor call, and ssh-keygen (args[0]) to
// verify SSHSIG signatures. JNA loads the host's librocky_prism_ffi for the
// replica (-Djna.library.path).
fun main(args: Array<String>) {
    val served = Probe(name = "served", count = 42, tags = listOf("ktor"), note = "okhttp")
    val body = PrismShared.json.encodeToString(Probe.serializer(), served).toByteArray()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/probe") { exchange ->
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
    server.createContext("/prism-events/json") { exchange ->
        val ok = exchange.requestHeaders.getFirst("Authorization") == "Bearer relay-token" &&
            exchange.requestURI.query == "since=1h"
        val stream = pushStreamLines.joinToString("\n", postfix = "\n").toByteArray()
        exchange.sendResponseHeaders(if (ok) 200 else 403, if (ok) stream.size.toLong() else -1)
        if (ok) exchange.responseBody.use { it.write(stream) } else exchange.close()
    }
    server.start()
    try {
        runBlocking {
            sharedTests("http://127.0.0.1:${server.address.port}/probe", served)
            approvalTests()
            hostFormatTests()
            apiTests("http://127.0.0.1:${server.address.port}")
            // librocky_prism_ffi from jna.library.path (shared/default.nix).
            replicaTests(Files.createTempDirectory("replica-test").resolve("prism").toString())
        }
    } finally {
        server.stop(0)
    }
    sshSigTests(args[0])
    println("shared tests passed on the JVM: ${PrismShared.version()}")
}

/** Signatures from SshSig that `ssh-keygen -Y verify` accepts, and rejects when the message changed. */
private fun sshSigTests(sshKeygen: String) {
    val work = Files.createTempDirectory("sshsig-test").toFile()
    val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
    val approval = Approval(id = "x", title = "Run the backup", source = "david-server", requestedAt = 1, expiresAt = 2)
    for (round in 0 until 16) {
        val pair = generator.generateKeyPair()
        val point = ecPoint(pair.public as ECPublicKey)
        val (message, namespace) =
            if (round % 2 == 0) Approvals.message(approval.copy(id = "x$round")) to Approvals.NAMESPACE
            else Enrollment.message("host$round") to Enrollment.NAMESPACE
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(SshSig.signedData(message, namespace))
            sign()
        }
        val signers = File(work, "signers").apply { writeText("owner namespaces=\"$namespace\" ${SshSig.publicKeyLine(point)}\n") }
        val sig = File(work, "message.sig").apply { writeText(SshSig.armor(namespace, point, der)) }
        val msg = File(work, "message").apply { writeBytes(message) }
        val verified = verify(sshKeygen, signers, sig, msg, namespace)
        msg.writeBytes(message + 'x'.code.toByte())
        val tampered = verify(sshKeygen, signers, sig, msg, namespace)
        check(verified && !tampered) { "SSHSIG round $round (verified $verified, tampered verified $tampered)" }
    }
}

private fun verify(sshKeygen: String, signers: File, sig: File, msg: File, namespace: String): Boolean =
    ProcessBuilder(sshKeygen, "-Y", "verify", "-f", signers.path, "-I", "owner", "-n", namespace, "-s", sig.path)
        .redirectInput(msg).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0
