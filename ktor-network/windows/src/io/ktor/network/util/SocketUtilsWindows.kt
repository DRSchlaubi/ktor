/*
 * Copyright 2014-2023 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.network.util

import kotlinx.cinterop.*
import platform.posix.*
import platform.windows.*

/*
 * Copyright 2014-2023 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

internal actual fun ktor_inet_ntop(
    family: Int,
    src: CValuesRef<*>?,
    dst: CValuesRef<ByteVar>?,
    size: socklen_t
): CPointer<ByteVar>? = TODO()

internal actual fun <T> unpack_sockaddr_un(
    sockaddr: sockaddr,
    block: (family: UShort, path: String) -> T
): T {
    val aaddress = sockaddr.ptr.reinterpret<sockaddr_un>().pointed
    val address = sockaddr.ptr.reinterpret<sockaddr_in>().pointed
    return block(address.sun_family, address.sun_path.toKString())
}

internal actual fun pack_sockaddr_un(
    family: UShort,
    path: String,
    block: (address: CPointer<sockaddr>, size: socklen_t) -> Unit
) {
    cValue<sockaddr_un> {
        strcpy(sun_path, path)
        sun_family = family

        block(ptr.reinterpret(), sizeOf<sockaddr_un>().convert())
    }
}
