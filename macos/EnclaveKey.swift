// This Mac's device key: a P-256 key in its Secure Enclave that signs only
// after Touch ID (or the login password), created on first use. It signs
// approvals and the Prism enrollment as SSHSIG (shared/: SshSig.kt): the
// Enclave signs the SSHSIG's signed data, and the shared code wraps the DER
// signature with the public key's point (x963Representation).
// What the Enclave hands out is an opaque, device-bound wrapping of the key,
// kept in Support.keyFile (0600); the private key itself never leaves the chip.
//
// Support also names the other files under ~/Library/Application Support/Prism:
// the approvals' history (Approvals.encodeHistory).

import CryptoKit
import Foundation
@preconcurrency import LocalAuthentication
import PrismShared
import Security

enum Support {
    static let directory = FileManager.default.homeDirectoryForCurrentUser
        .appendingPathComponent("Library/Application Support/Prism", isDirectory: true)
    static let keyFile = directory.appendingPathComponent("secure-enclave-key")
    static let historyFile = directory.appendingPathComponent("history.json")
    /// Creates `url` as a directory only the owner can enter.
    static func makePrivateDirectory(_ url: URL) throws {
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        guard chmod(url.path, 0o700) == 0 else { throw POSIXError.current("chmod \(url.path)") }
    }
}

struct EnclaveKey {
    enum Failure: LocalizedError {
        case noSecureEnclave
        var errorDescription: String? { "This Mac has no Secure Enclave." }
    }

    let dataRepresentation: Data
    let publicKey: P256.Signing.PublicKey

    /// The public key's uncompressed point (04 || X || Y), as SshSig takes it.
    var point: Data { publicKey.x963Representation }
    var publicKeyLine: String { SshSig.shared.publicKeyLine(point: point.kotlin) }

    /// Loads the key, creating it on first use. An existing key file is never
    /// replaced: its public key is registered elsewhere (the Prism server, fleet).
    static func loadOrCreate() throws -> EnclaveKey {
        if let data = try? Data(contentsOf: Support.keyFile) {
            return try EnclaveKey(data)
        }
        guard SecureEnclave.isAvailable else { throw Failure.noSecureEnclave }
        try Support.makePrivateDirectory(Support.directory)

        var error: Unmanaged<CFError>?
        guard
            let access = SecAccessControlCreateWithFlags(
                nil, kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
                [.privateKeyUsage, .userPresence], &error)
        else { throw error!.takeRetainedValue() as Error }
        let key = try SecureEnclave.P256.Signing.PrivateKey(accessControl: access)

        // O_EXCL: if another process created the key meanwhile, keep theirs.
        let fd = open(Support.keyFile.path, O_WRONLY | O_CREAT | O_EXCL, 0o600)
        if fd < 0 {
            if errno == EEXIST { return try EnclaveKey(Data(contentsOf: Support.keyFile)) }
            throw POSIXError.current("create \(Support.keyFile.path)")
        }
        let handle = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
        try handle.write(contentsOf: key.dataRepresentation)
        try handle.synchronize()
        return try EnclaveKey(key.dataRepresentation)
    }

    private init(_ data: Data) throws {
        let key = try SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: data)
        dataRepresentation = data
        publicKey = key.publicKey
    }

    /// Signs `data` (an SSHSIG's signed data) and returns the DER ECDSA
    /// signature. Blocks while macOS asks for Touch ID, whose sheet shows
    /// `context.localizedReason`, unless `context` was evaluated already;
    /// run it off the main thread.
    func sign(_ data: Data, context: LAContext) throws -> Data {
        let key = try SecureEnclave.P256.Signing.PrivateKey(
            dataRepresentation: dataRepresentation, authenticationContext: context)
        return try key.signature(for: data).derRepresentation
    }

    /// Signs `message` as SSHSIG in `namespace`, as `ssh-keygen -Y sign` would.
    func sign(message: Data, namespace: String, context: LAContext) throws -> String {
        let der = try sign(Data(SshSig.shared.signedData(message: message.kotlin, namespace: namespace)), context: context)
        return SshSig.shared.armor(namespace: namespace, point: point.kotlin, derSignature: der.kotlin)
    }
}

extension POSIXError {
    /// The current errno as an error, with what failed.
    static func current(_ what: String) -> NSError {
        let code = errno
        return NSError(
            domain: NSPOSIXErrorDomain, code: Int(code),
            userInfo: [NSLocalizedDescriptionKey: "\(what): \(String(cString: strerror(code)))"])
    }
}
