@file:Suppress("ktlint:standard:max-line-length") // Generated test vectors.

package uno.lux.wormhole.crypto

// Generated with Python's `cryptography` library (RFC 7748 X25519, OpenSSL-backed).
// ITERATE_1/ITERATE_1000 independently reproduce the RFC 7748 section 5.2 iterated test.
internal object Curve25519Vectors {
    class Vector(
        val scalar: String,
        val u: String,
        val output: String,
    )

    val all: List<Vector> =
        listOf(
            Vector(
                "159d26d198bc6eef021131d545ba86ceff4ab1cd24ba15eff751244b314ad2b2",
                "f2a25fa23a90c98c0a7850ac9ef2e0baf343287c078be2ee98df56998ece9f76",
                "c687d3b34dfbde61ec2cbaa4f065501973956f7ac46e4a96a3d7416a00cc8602",
            ),
            Vector(
                "dd27cf9bb399953976877eabb8912346ab70f07aa67c4f4306b2b7b0fb88fea1",
                "cdad01052b9e93b3c31fbe8770647ad426b22eeda36bbdbaaab9977801cb42b8",
                "56416f1d660bb5c5f33616a78458da3d80576925056f950693ddc44c6e50e46d",
            ),
            Vector(
                "3e0c1a72ef46804d9c8f1e2032d3709ca3d12a35271c35b0565cabdb1fb9f7ca",
                "1d7af7a2dd03ef95ce5d8a98cd9e4d892c64d81b10b9f09ade78bcf222db64ed",
                "3467b48b765b1fa485b2e063e973c78d1fc1b7e962da893f4045315d64431167",
            ),
        )

    const val ITERATE_1: String = "422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079"
    const val ITERATE_1000: String = "684cf59ba83309552800ef566f2f4d3c1c3887c49360e3875f2eb94d99532c51"

    const val ALICE_PRIVATE: String = "6943d31c4319698d15780cc8d04f76aba1424151912210747af03fc56f24719b"
    const val BOB_PRIVATE: String = "1cba19490f1e1f5b069bec53f6c0694e4c1660720f72d984766cf1c01d9cb883"
    const val ALICE_PUBLIC: String = "d97ea418530f549203e0002c1a6b2ef4abfa325c33920407f3d3613cb6ac5b09"
    const val BOB_PUBLIC: String = "5a88cc237bf8119c2287a21e4c2b49a4ace5dd28096b2abe517011b33723ea34"
    const val SHARED_SECRET: String = "45e9f721910c0bdcedbb9d0d8daa382ef59b7d59bf534a24ace0058aa1475971"
}
