# Keep VpnService and WireGuard backend intact
-keep class com.wireguard.** { *; }
-keep class com.apolos.shield.vpn.** { *; }
-dontwarn com.wireguard.**
