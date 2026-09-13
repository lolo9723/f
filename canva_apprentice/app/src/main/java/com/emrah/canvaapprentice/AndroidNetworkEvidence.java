package com.emrah.canvaapprentice;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/** Reads Android's current validated-network state without making network requests. */
public final class AndroidNetworkEvidence {
    private AndroidNetworkEvidence() {}

    public static Boolean currentValidated() {
        AgentAccessibilityService service = AgentAccessibilityService.INSTANCE;
        if (service == null) return null;
        try {
            ConnectivityManager cm = (ConnectivityManager) service.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            Network network = cm.getActiveNetwork();
            if (network == null) return Boolean.FALSE;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) return Boolean.FALSE;
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (SecurityException | RuntimeException e) {
            return null;
        }
    }
}
