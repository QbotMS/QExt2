package com.qext2.primary.active

/**
 * Most miedzy BroadcastReceiverem (tap na komunikacie) a menedzerem komunikatow
 * zyjacym w instancji CompositeActiveDataType. Rejestracja przy konstrukcji
 * datatype; celowo BEZ wyrejestrowania — dismiss przy schowanym polu jest
 * nieszkodliwy (brak nakladki = brak tapow), a nowa instancja nadpisuje wpis.
 */
object ActiveMessageBus {
    @Volatile
    var manager: ActiveMessageManager? = null
}
