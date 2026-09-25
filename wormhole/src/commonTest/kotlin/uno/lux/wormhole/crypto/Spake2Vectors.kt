@file:Suppress("ktlint:standard:max-line-length") // Generated test vectors.

package uno.lux.wormhole.crypto

// Generated with python-spake2 0.9 (spake2.SPAKE2_Symmetric, Ed25519 parameters).
// Password "4-purple-sausages", idSymmetric = the default app ID, entropy = sha512("alice" / "bob").
internal object Spake2Vectors {
    const val S_ELEMENT = "6f00dae87c1be1a73b5922ef431cd8f57879569c222d22b1cd71e8546ab8e6f1"
    const val M_ELEMENT = "15cfd18e385952982b6a8f8c7854963b58e34388c8e6dae891db756481a02312"
    const val BASE = "5866666666666666666666666666666666666666666666666666666666666666"
    const val BASE_TIMES_12345 = "ef4f62f8479733ad879cfaced3c89a9c39dd4fc795ef2efa1c3eafe4d729a081"
    const val BASE_TIMES_L_MINUS_1 = "58666666666666666666666666666666666666666666666666666666666666e6"
    const val BASE_PLUS_S = "ce9f9c3ba53323dc35b08d432c24233700656ab4082f0e8978cc4e91f44e2da7"
    const val SCALAR_PW = "dc33d78c756863311eff13659be4d36f722a80caabe615c9aa1192cdf1b66e01" // password_to_scalar(b"4-purple-sausages")
    const val SCALAR_EMPTY = "8f9a0740eed9b9410a9624867bf3927f2bb0ace55dd20d8a55482865826f0d0d" // password_to_scalar(b"")
    const val RANDOM_SCALAR_INPUT = "408b27d3097eea5a46bf2ab6433a7234a33d5e49957b13ec7acc2ca08e1a13c75272c90c8d3385d47ede5420a7a9623aad817d9f8a70bd100a0acea7400daa59"
    const val RANDOM_SCALAR = "3d9bd48f56f962d12b3a4c4f787b0396575ad8f9d7295f8d3e4df2b1cdf87306"
    const val ALICE_ENTROPY = "408b27d3097eea5a46bf2ab6433a7234a33d5e49957b13ec7acc2ca08e1a13c75272c90c8d3385d47ede5420a7a9623aad817d9f8a70bd100a0acea7400daa59"
    const val BOB_ENTROPY = "0416a26ba554334286b1954918ecad7ba6c33575b49df915ff3367b5cef7ecd93b1f0b436636667b27b363011543971f1c81c3151d5ef72733501c1ff33c34af"
    const val ALICE_MSG = "53d3126c01d77a3966ffbc8037c9828df0b6380392b707f679b48efe44f22b27c1"
    const val BOB_MSG = "53b4fb266abf5ccefef2c8fcd4709335329e3ae5483ca1f966727c552ec2a75e91"
    const val SHARED_KEY = "b2424f7ba85187820ca24aef439717c36817e5e460aa31d73bcfb3876e401096"
}
