@file:Suppress("ktlint:standard:max-line-length") // Generated test vectors.

package uno.lux.wormhole.crypto

// Generated with Python's hashlib.blake2s / hmac (RFC 7693 BLAKE2s, unkeyed 32-byte digest).
// The "abc" vector matches the well-known published BLAKE2s-256("abc") test value.
internal object Blake2sVectors {
    class HashVector(
        val label: String,
        val message: String,
        val digest: String,
    )

    class HmacVector(
        val label: String,
        val key: String,
        val message: String,
        val mac: String,
    )

    val hashes: List<HashVector> =
        listOf(
            HashVector(
                "empty",
                "",
                "69217a3079908094e11121d042354a7c1f55b6482ca1a51e1b250dfd1ed0eef9",
            ),
            HashVector(
                "abc",
                "616263",
                "508c5e8c327c14e2e1a72ba34eeb452f37458b209ed63a294d999b4c86675982",
            ),
            HashVector(
                "one-block-63",
                "f8b5266dc73ec99ed8e0e5f02f90e633a751ab9509ac773b619d21171f3b3c579afadb0beb8845d33f8820ac426e0016848d3a638f4e8d89632ba41a19ad40",
                "0d7c140416442cfda3f1613a8ac6c7ec21bff9eada291622db9f27715a181828",
            ),
            HashVector(
                "one-block-64",
                "891222d019bb75daaa3bc8e628f1930c694c5042995dcfc908b1aed15df7981904307145f230e7403ec4826a9e34533b037b40054fca1f11fd83c91575c863c2",
                "f2208e360932ff78573d28695b1f4a20d3a176940b99c03cfd95ee5ad063b765",
            ),
            HashVector(
                "two-blocks-65",
                "c6e15b139cf12e23dd691fb00e6489dc38efbc20730dff5c46886ed7f1d9f27c7d56662f30fa9a6b03beb97dc75d1fb795e45db6ac5fe622a96c9a4fccc5f0a1d1",
                "b758ac7e5e5bdfa032615d6ec5565e800921f4ea787a546644dfdb8d15877645",
            ),
            HashVector(
                "two-blocks-128",
                "ff07c38318163c1dbf6c15dced819815900b6c4142f59f5c051b67dae1adb1c68113165dad8fc107e48a72aae389d106a66933ac5f265f8b59de439e43d492c3251259e9c41fe15071ac78c278cff3298b28a0a7e80446fd6c3d718385f76aea3cb742ca2a6a0ecba9438deae3dd731683fe585b6c429a866d6d6d06a4276270",
                "2b5554abab0bc773ae7a4f52a77801de61dcf7088031ecc788354709f90a30f7",
            ),
            HashVector(
                "multi-300",
                "d41333ca92c27459bcdb03f0cc299ca31a225150e8eb23b869f54bf926adb1cfd2eb054d422fbfa6177616f464ad6c8853ec4ec47f7dc8a72acb7eed56ba7890fd8516def7a6ef32e0618f8d576d4d770dc25ac1acf62658fc09ec13e6e2059a80b4e18111d71b476a5a20d636177afd1d70b9e0a91c038a1decd804d89e72aa39b9ea15d03a0c48df3b4658f7232b2f06e440e223868df521360a17e8c3b293d3cb031fb3cb9f27912e012d344aa586be15adf3b6d033976e7ce7f3df6f7693bbd98ab03cbc14e95b5fd5fee22c229aa5547af24873bdaa45c7d4cb64ddee7f9cac2ec3749e1bd803b9f225e977788b92aa5b84e40bbe01627ec86b2a3c874232433d280e80af6ed41480ea18ef807f61ff4d702b6e16e18136984323d3b740c0fd795334a8044e2102eacd",
                "13298face82f65a33a55402a058c59dbc5da21c099c7a0f96241a5cae4900367",
            ),
        )

    val hmacs: List<HmacVector> =
        listOf(
            HmacVector(
                "short-key",
                "edca35c5c615ee693f5d2d8e679d3f91",
                "bba5dd43e8eb1dfc00b5",
                "aa585d01bdf32a46c78d99890dc54a2e3a763447e0abb8e1a99f1dd898439c2c",
            ),
            HmacVector(
                "block-key",
                "e1f3113c9f0f3e964d24334ed94e9bdf6ab348a81c8205f34bb00ed2f9d6bd5c6a8e2e27023bc02dabf5fc008cd90fcd2cc9739e1a4ca64c21e4087df6328c34",
                "",
                "2ccf9ddcf40248d63d8a3bdeb1e47c65f0e6bdfc18b8995d7172a92fa99e9e5b",
            ),
            HmacVector(
                "long-key",
                "b5f5a131fc38eef892636ee1b3ab95fed8a94c47321c1c2d19f6741345e6b25d25d84187e9279b30cfcf79dd2061ddd0fc0f25bb0fb20778cf0470b8cea1d6a52a2b121a6a5dc724c2e109945a86ba65497cb1ada8b77a3e430ed439724ce4eb1da7dde7",
                "e903779960d56b986f314d61b0c04c47fa52353ea9fb90de86cf76cb83f2515574266dbec5dd76681f0727f14402d85b645c5927d6eb66b68eea1bc30d261eab73d4f029ae14c95731b661f05e1506e71cd9aeab33946ed275a870d04cc879a7da242850fc52666b2987d4953f408c5f0ac58c69d58a01a6f1084b1e4f526a394dc56207c5d0377009419a950b96252f3be380d72c1cd4583afb4b53bcfc6d82a0b9dd4b002678b434dce3331eec50b9f182268163d6bb7edfb6dc6fc57773c463652fc44e440c27",
                "f7e5e5a8cddccad6f0e2ba7f85f987c0774f8007e16bc47b368cff9b616d31a8",
            ),
            HmacVector(
                "dilation-key-like",
                "964f21344a1c636272abc2b935cd591c0f40c52c53bcdddbfe6a89039864fbfd",
                "851c7d8bbe016a9c57bb060c04ec4cefad805fe8d9d861941367b6237608dab1",
                "d0f1f3429a366fe7a15ac5050739834042c120840c407930a2856bf6638a4417",
            ),
        )
}
