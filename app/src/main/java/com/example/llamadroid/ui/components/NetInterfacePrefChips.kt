package com.example.llamadroid.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.util.DeviceAddresses

/**
 * Auto / VPN / LAN chips for a helper's preferred network interface, with a line naming the address in use.
 * [pref] is one of [DeviceAddresses.PREF_AUTO], [DeviceAddresses.PREF_VPN], [DeviceAddresses.PREF_LAN].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NetInterfacePrefChips(
    pref: String,
    activeHost: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    vpnAddresses: Set<String> = emptySet()
) {
    val current = DeviceAddresses.normalizePref(pref)
    val options = listOf(
        DeviceAddresses.PREF_AUTO to R.string.net_pref_auto,
        DeviceAddresses.PREF_VPN to R.string.net_pref_vpn,
        DeviceAddresses.PREF_LAN to R.string.net_pref_lan
    )
    val inUse = if (DeviceAddresses.isVpnAddress(activeHost, vpnAddresses)) {
        stringResource(R.string.net_pref_in_use_vpn, activeHost)
    } else {
        stringResource(R.string.net_pref_in_use_lan, activeHost)
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, label) ->
                FilterChip(
                    selected = current == value,
                    onClick = { onChange(value) },
                    label = { Text(stringResource(label), maxLines = 1) }
                )
            }
        }
        Text(
            text = inUse,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
