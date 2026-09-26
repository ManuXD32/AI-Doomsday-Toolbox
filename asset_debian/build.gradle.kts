import java.security.MessageDigest

plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("asset_debian")
    dynamicDelivery {
        // Debian is part of the installed app experience. It must not be fetched as an
        // executable package after install; apt remains an explicit guest-user action.
        deliveryType.set("install-time")
    }
}

val verifyDebianAssetPayloads by tasks.registering {
    group = "verification"
    description = "Verify the Debian and harness archives before packaging the asset pack."

    val assets = layout.projectDirectory.dir("src/main/assets")
    val archives = listOf("debian/rootfs.tar.xz", "harness/adt-harness-linux-arm64.tar.xz")
    inputs.files(archives.flatMap { listOf(assets.file(it), assets.file("$it.sha256")) })
    val identityChecker = layout.projectDirectory.file("sanitize_debian_rootfs.py")
    inputs.file(identityChecker)

    doLast {
        archives.forEach { archive ->
            val payload = assets.file(archive).asFile
            val checksum = assets.file("$archive.sha256").asFile
            check(payload.isFile && checksum.isFile) {
                "Missing Debian asset or checksum: $archive"
            }

            val checksumParts = checksum.readText().trim().split(Regex("\\s+"), limit = 2)
            check(checksumParts.size == 2 && checksumParts[1] == payload.name &&
                checksumParts[0].matches(Regex("[0-9a-fA-F]{64}"))) {
                "Invalid checksum file for Debian asset: $archive"
            }

            val digest = MessageDigest.getInstance("SHA-256")
            payload.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            check(actual.equals(checksumParts[0], ignoreCase = true)) {
                "Debian asset checksum mismatch: $archive. Restore Git LFS assets before building."
            }
        }
        val identityCheck = providers.exec {
            commandLine("python3", identityChecker.asFile.absolutePath,
                assets.file("debian/rootfs.tar.xz").asFile.absolutePath, "--check")
        }
        logger.lifecycle(identityCheck.standardOutput.asText.get().trim())
    }
}

tasks.named("generateAssetPackManifest") {
    dependsOn(verifyDebianAssetPayloads)
}
