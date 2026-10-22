package ru.mobile.mys

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid

/** Один экземпляр libsodium на процесс (загрузка нативной библиотеки — один раз). */
val sodium: LazySodiumAndroid by lazy { LazySodiumAndroid(SodiumAndroid()) }
