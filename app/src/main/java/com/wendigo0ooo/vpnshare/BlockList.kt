package com.wendigo0ooo.vpnshare

import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object ConnectionRegistry {

    private val map = ConcurrentHashMap<String, MutableSet<Socket>>()

    private fun setFor(ip: String): MutableSet<Socket> =
        map.getOrPut(ip) { Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>()) }

    fun register(ip: String, socket: Socket) {
        setFor(ip).add(socket)
    }

    fun unregister(ip: String, socket: Socket) {
        val set = map[ip] ?: return
        set.remove(socket)
        if (set.isEmpty()) map.remove(ip, set)
    }

    fun closeAll(ip: String) {
        val set = map[ip] ?: return
        for (socket in set) Relay.closeQuietly(socket)
        set.clear()
    }

    fun reset() {
        for (set in map.values) {
            for (socket in set) Relay.closeQuietly(socket)
        }
        map.clear()
    }
}

object BlockList {

    private val blocked = Collections.synchronizedSet(HashSet<String>())

    fun isBlocked(ip: String?): Boolean = ip != null && blocked.contains(ip)

    fun block(ip: String) {
        blocked.add(ip)
        ConnectionRegistry.closeAll(ip)
    }

    fun unblock(ip: String) {
        blocked.remove(ip)
    }

    fun reset() {
        blocked.clear()
    }
}
