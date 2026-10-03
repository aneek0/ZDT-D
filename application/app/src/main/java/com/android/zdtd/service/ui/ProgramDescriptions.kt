package com.android.zdtd.service.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.android.zdtd.service.R

internal fun toolDisplayName(id: String, rawName: String? = null): String {
  val normalizedRaw = rawName?.trim()?.takeIf { it.isNotEmpty() }
  return when (id) {
    "dnscrypt" -> normalizedRaw?.takeUnless { it.equals("dnscrypt", ignoreCase = true) } ?: "DNSCrypt"
    "openvpn" -> normalizedRaw?.takeUnless { it.equals("openvpn", ignoreCase = true) } ?: "OpenVPN"
    "nfqws2" -> normalizedRaw?.takeUnless { it.equals("nfqws2", ignoreCase = true) || it.equals("zapret2", ignoreCase = true) || it.equals("zapret 2", ignoreCase = true) } ?: "Zapret 2"
    "byedpi" -> normalizedRaw?.takeUnless { it.equals("byedpi", ignoreCase = true) } ?: "ByeDPI"
    "wireproxy" -> normalizedRaw?.takeUnless { it.equals("wireproxy", ignoreCase = true) } ?: "WireProxy"
    "tor" -> normalizedRaw?.takeUnless { it.equals("tor", ignoreCase = true) } ?: "Tor"
    "mihomo" -> normalizedRaw?.takeUnless { it.equals("mihomo", ignoreCase = true) } ?: "Mihomo"
    "mieru" -> normalizedRaw?.takeUnless { it.equals("mieru", ignoreCase = true) } ?: "mieru"
    "amneziawg" -> normalizedRaw?.takeUnless { it.equals("amneziawg", ignoreCase = true) } ?: "AmneziaWG"
    "tgwsproxy" -> normalizedRaw?.takeUnless { it.equals("tgwsproxy", ignoreCase = true) } ?: "Telegram WS Proxy"
    else -> normalizedRaw ?: id
  }
}

@Composable
internal fun toolDescription(id: String): String {
  return when (id) {
    "dnscrypt" -> stringResource(R.string.apps_list_desc_dnscrypt)
    "operaproxy" -> stringResource(R.string.apps_list_desc_operaproxy)
    "nfqws2" -> stringResource(R.string.apps_list_desc_nfqws2)
    "byedpi" -> stringResource(R.string.apps_list_desc_byedpi)
    "sing-box" -> stringResource(R.string.apps_list_desc_singbox)
    "wireproxy" -> stringResource(R.string.apps_list_desc_wireproxy)
    "tor" -> stringResource(R.string.apps_list_desc_tor)
    "myproxy" -> stringResource(R.string.apps_list_desc_myproxy)
    "myprogram" -> stringResource(R.string.apps_list_desc_myprogram)
    "openvpn" -> stringResource(R.string.apps_list_desc_openvpn)
    "amneziawg" -> stringResource(R.string.apps_list_desc_amneziawg)
    "tun2socks" -> stringResource(R.string.apps_list_desc_tun2socks)
    "myvpn" -> stringResource(R.string.apps_list_desc_myvpn)
    "mihomo" -> stringResource(R.string.apps_list_desc_mihomo)
    "mieru" -> stringResource(R.string.apps_list_desc_mieru)
    "tgwsproxy" -> stringResource(R.string.apps_list_desc_tgwsproxy)
    else -> stringResource(R.string.apps_list_desc_default)
  }
}
