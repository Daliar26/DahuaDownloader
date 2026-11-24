package ru.dahuatools.downloader

/**
 * Reflection boundary for the gomobile-generated AAR. Keeping the binding behind
 * reflection lets the regular IDE source set remain readable; CI generates the AAR
 * before Gradle compilation.
 */
object P2pBridge {
    private const val CLASS_NAME = "ru.dahuatools.p2p.P2pbridge"

    fun start(serial: String, username: String, password: String, localPort: Int, devicePort: Int): String {
        return try {
            val type = Class.forName(CLASS_NAME)
            val method = type.methods.firstOrNull {
                it.name.equals("start", ignoreCase = true) && it.parameterTypes.size == 5
            } ?: return "В P2P-библиотеке не найден метод запуска"
            method.invoke(null, serial, username, password, localPort, devicePort) as? String ?: ""
        } catch (e: ClassNotFoundException) {
            "P2P-библиотека не собрана. Обновите приложение до последней сборки."
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            "Ошибка запуска P2P: ${cause.message ?: cause.javaClass.simpleName}"
        }
    }

    fun stop() {
        runCatching {
            val type = Class.forName(CLASS_NAME)
            type.methods.firstOrNull {
                it.name.equals("stop", ignoreCase = true) && it.parameterTypes.isEmpty()
            }?.invoke(null)
        }
    }
}
