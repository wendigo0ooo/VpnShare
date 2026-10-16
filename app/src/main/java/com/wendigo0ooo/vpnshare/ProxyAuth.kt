package com.wendigo0ooo.vpnshare

object ProxyAuth {

    @Volatile
    var enabled = false

    @Volatile
    var username = ""

    @Volatile
    var password = ""

    fun check(user: String, pass: String): Boolean =
        !enabled || (user == username && pass == password)
}
